package dev.dramaticfaces;

/**
 * The recent voice of one player: how loud each 20 ms audio frame was, how
 * "bright" it sounded and its pitch. Written by the voice chat's audio thread,
 * read by the render thread, so everything is synchronized.
 *
 * <p>Nothing is recorded and no words are understood; only loudness, pitch and
 * a rough tone measure are kept, for the last 2 seconds.
 */
final class VoiceTrack {
	/** 100 frames of 20 ms = 2 seconds. */
	private static final int HISTORY = 100;
	private static final long FRAME_NANOS = 20_000_000L;
	/** Frames quieter than this are pauses between words, not speech. */
	static final float SPEECH = 0.15f;
	/** Laughs are judged on this much recent audio. */
	private static final long LAUGH_WINDOW = 1_800_000_000L;
	/** How far the level must rise and fall again (about 5 dB) to count as one "ha". */
	private static final float BURST_DELTA = 0.12f;
	/** Quieter peaks than this are breathing or noise, not a "ha". */
	private static final float BURST_FLOOR = 0.2f;
	/** A pause this long ends a laugh; the next "ha" starts a new one. */
	private static final long LAUGH_BREAK = 600_000_000L;
	/** A laugh only counts while it's still going. */
	private static final long LAUGH_ONGOING = 450_000_000L;
	/** Marks a frame without a pitch (unvoiced: "s", "h", whispering, noise). */
	static final float NO_PITCH = Float.NaN;

	/** Rough mouth shapes for lip sync, guessed from where the sound's energy sits. */
	enum Shape {
		/** Silent, or a hummed "m", "b", "p". */
		CLOSED,
		/** Open vowels: "a", "ah". */
		A,
		/** Round vowels, all low energy: "o", "oo", "w". */
		O,
		/** Bright vowels with lots of high energy: "e", "ee", "i". */
		E,
		/** Hissing without a voice: "s", "sh", "f", "ch". Teeth together. */
		S
	}

	private static final Shape[] SHAPES = Shape.values();

	private final float[] levels = new float[HISTORY];
	private final float[] brightness = new float[HISTORY];
	/** Pitch in semitones above 100 Hz, or {@link #NO_PITCH}. */
	private final float[] pitches = new float[HISTORY];
	private final byte[] shapes = new byte[HISTORY];
	/** Share of the last frame's energy below about 600 Hz and above about 2 kHz. */
	private float lowShare;
	private float highShare;
	/** When each frame was spoken, on an even 20 ms grid so network jitter doesn't blur the rhythm. */
	private final long[] times = new long[HISTORY];
	/** Whether silence came right before this frame. */
	private final boolean[] gapBefore = new boolean[HISTORY];
	private int next;
	private int count;
	private boolean whispering;
	private long lastArrival;
	private long lastFrame;
	/** Scratch buffer for the pitch search, reused so the audio thread doesn't allocate. */
	private float[] reduced = new float[0];

	/**
	 * What the render thread needs, measured at one moment.
	 *
	 * @param loud average level of the recent speech, pauses left out
	 * @param speech how many of the last 25 frames had speech in them
	 * @param voicing share of the recent speech that had a pitch (whispers have almost none)
	 * @param pitch average recent pitch in semitones above 100 Hz, or NaN
	 * @param spread how much the pitch moved around over the last 1.5 s, in semitones
	 * @param bursts how many "ha"-like bursts the current run has
	 * @param laugh how much the current run sounds like laughing, 0 to 1
	 * @param laughPitch average pitch of those bursts, or NaN
	 */
	record Snapshot(boolean active, float level, float loud, int speech, float voicing, float pitch, float spread,
			float bright, int bursts, float laugh, float laughPitch, boolean whispering, long silentNanos,
			Shape shape, float low, float high) {
	}

	synchronized void push(short[] audio, boolean whisper, long now) {
		if (audio == null || audio.length == 0) {
			return;
		}
		double sum = 0.0;
		int crossings = 0;
		for (int i = 0; i < audio.length; i++) {
			sum += (double) audio[i] * audio[i];
			if (i > 0 && (audio[i - 1] < 0) != (audio[i] < 0)) {
				crossings++;
			}
		}
		double rms = Math.sqrt(sum / audio.length);
		// -45 dBFS (quiet) maps to 0, -5 dBFS (screaming into the mic) to 1.
		double db = 20.0 * Math.log10(Math.max(rms, 1.0) / 32768.0);
		float level = (float) Math.max(0.0, Math.min(1.0, (db + 45.0) / 40.0));
		// Packets arrive in bunches over the network; space them 20 ms apart unless there was a real pause.
		long frame = lastFrame == 0L ? now : Math.max(lastFrame + FRAME_NANOS, now - 3 * FRAME_NANOS);
		levels[next] = level;
		brightness[next] = crossings / (float) audio.length;
		lowShare = 0.0f;
		highShare = 0.0f;
		pitches[next] = level >= SPEECH * 0.7f ? pitch(audio) : NO_PITCH;
		shapes[next] = (byte) shape(level, pitches[next]).ordinal();
		times[next] = frame;
		gapBefore[next] = lastFrame == 0L || frame - lastFrame > 2 * FRAME_NANOS;
		next = (next + 1) % HISTORY;
		count = Math.min(count + 1, HISTORY);
		whispering = whisper;
		lastArrival = now;
		lastFrame = frame;
	}

	/**
	 * The pitch of one frame of 48 kHz audio, in semitones above 100 Hz, or
	 * {@link #NO_PITCH} if it isn't voiced. Looks for how far the wave has to be
	 * shifted to line up with itself (autocorrelation), on a 16 kHz copy to keep it cheap.
	 */
	private float pitch(short[] audio) {
		int n = audio.length / 3;
		if (n < 200) {
			return NO_PITCH;
		}
		if (reduced.length < n) {
			reduced = new float[n];
		}
		float mean = 0.0f;
		for (int j = 0; j < n; j++) {
			reduced[j] = (audio[3 * j] + audio[3 * j + 1] + audio[3 * j + 2]) / 3.0f;
			mean += reduced[j];
		}
		mean /= n;
		for (int j = 0; j < n; j++) {
			reduced[j] -= mean;
		}
		bands(n);
		// 16000 / 32 = 500 Hz up to 16000 / 200 = 80 Hz.
		int minLag = 32;
		int maxLag = Math.min(200, n - 100);
		float[] score = new float[maxLag + 2];
		float best = 0.0f;
		for (int lag = minLag; lag <= maxLag; lag++) {
			double xy = 0.0;
			double xx = 0.0;
			double yy = 0.0;
			for (int i = 0; i + lag < n; i++) {
				float x = reduced[i];
				float y = reduced[i + lag];
				xy += x * y;
				xx += x * x;
				yy += y * y;
			}
			score[lag] = xx > 0.0 && yy > 0.0 ? (float) (xy / Math.sqrt(xx * yy)) : 0.0f;
			best = Math.max(best, score[lag]);
		}
		if (best < 0.55f) {
			return NO_PITCH;
		}
		// The shortest strong match is the real pitch; longer ones are its multiples.
		for (int lag = minLag + 1; lag < maxLag; lag++) {
			if (score[lag] >= best * 0.88f && score[lag] >= score[lag - 1] && score[lag] >= score[lag + 1]) {
				double hz = 16000.0 / lag;
				return (float) (12.0 * Math.log(hz / 100.0) / Math.log(2.0));
			}
		}
		return NO_PITCH;
	}

	/** Splits the frame's energy into low, middle and high, with two simple filters. */
	private void bands(int n) {
		float low = 0.0f;
		float belowHigh = 0.0f;
		double lowEnergy = 0.0;
		double highEnergy = 0.0;
		double energy = 0.0;
		for (int j = 0; j < n; j++) {
			float x = reduced[j];
			// One-pole low-pass filters at about 600 Hz and 2 kHz (at 16 kHz).
			low += 0.21f * (x - low);
			belowHigh += 0.544f * (x - belowHigh);
			float high = x - belowHigh;
			lowEnergy += low * low;
			highEnergy += high * high;
			energy += x * x;
		}
		if (energy > 0.0) {
			lowShare = (float) (lowEnergy / energy);
			highShare = (float) (highEnergy / energy);
		}
	}

	private Shape shape(float level, float pitch) {
		if (level < SPEECH) {
			return Shape.CLOSED;
		}
		if (Float.isNaN(pitch)) {
			// No voice: a hiss if it's all high energy, otherwise a breathy open "h".
			return highShare > 0.3f ? Shape.S : Shape.A;
		}
		if (level < 0.3f && lowShare > 0.9f) {
			return Shape.CLOSED;
		}
		if (lowShare > 0.8f && highShare < 0.04f) {
			return Shape.O;
		}
		if (highShare > 0.12f) {
			return Shape.E;
		}
		return Shape.A;
	}

	/** Whether sound came in during the last moment; cheaper than a full snapshot. */
	synchronized boolean talking(long now) {
		return count > 0 && now - lastArrival < 300_000_000L;
	}

	synchronized Snapshot snapshot(long now) {
		if (count == 0) {
			return new Snapshot(false, 0.0f, 0.0f, 0, 0.0f, NO_PITCH, 0.0f, 0.0f, 0, 0.0f, NO_PITCH, false, Long.MAX_VALUE,
					Shape.CLOSED, 0.0f, 0.0f);
		}
		int newest = (next - 1 + HISTORY) % HISTORY;
		long silent = now - lastArrival;
		boolean active = silent < 7 * FRAME_NANOS;
		float level = silent < 6 * FRAME_NANOS ? levels[newest] : 0.0f;
		long end = lastFrame + silent;

		// The last half second of speech, leaving out the pauses between words.
		float loud = 0.0f;
		float bright = 0.0f;
		int speech = 0;
		int voiced = 0;
		float pitch = 0.0f;
		// Pitch movement over a longer stretch, to tell lively from flat, monotone speech.
		float pitchSum = 0.0f;
		float pitchSquares = 0.0f;
		int pitchCount = 0;
		for (int k = 0; k < count; k++) {
			int i = (newest - k + HISTORY) % HISTORY;
			long age = end - times[i];
			if (age > 1_500_000_000L) {
				break;
			}
			boolean hasPitch = !Float.isNaN(pitches[i]);
			if (hasPitch) {
				pitchSum += pitches[i];
				pitchSquares += pitches[i] * pitches[i];
				pitchCount++;
			}
			if (age > 500_000_000L || levels[i] < SPEECH) {
				continue;
			}
			loud += levels[i];
			bright += brightness[i];
			speech++;
			if (hasPitch) {
				pitch += pitches[i];
				voiced++;
			}
		}
		if (speech > 0) {
			loud /= speech;
			bright /= speech;
		}
		float spread = 0.0f;
		if (pitchCount > 3) {
			float mean = pitchSum / pitchCount;
			spread = (float) Math.sqrt(Math.max(0.0f, pitchSquares / pitchCount - mean * mean));
		}

		Laugh laugh = new Laugh();
		for (int k = count - 1; k >= 0; k--) {
			int i = (newest - k + HISTORY) % HISTORY;
			if (end - times[i] > LAUGH_WINDOW) {
				continue;
			}
			if (gapBefore[i]) {
				laugh.add(0.0f, 0.0f, NO_PITCH, times[i] - FRAME_NANOS);
			}
			laugh.add(levels[i], brightness[i], pitches[i], times[i]);
		}
		boolean ongoing = laugh.count > 0 && end - laugh.at[laugh.count - 1] < LAUGH_ONGOING;
		// The mouth shape most of the last three frames had, so one odd frame doesn't make it flicker.
		int[] votes = new int[SHAPES.length];
		for (int k = 0; k < Math.min(3, count); k++) {
			votes[shapes[(newest - k + HISTORY) % HISTORY]]++;
		}
		Shape shape = SHAPES[shapes[newest]];
		for (Shape s : SHAPES) {
			if (votes[s.ordinal()] >= 2) {
				shape = s;
			}
		}
		if (!active) {
			shape = Shape.CLOSED;
		}
		return new Snapshot(active, level, loud, speech, speech > 0 ? voiced / (float) speech : 0.0f,
				voiced > 0 ? pitch / voiced : NO_PITCH, spread, bright, laugh.count,
				ongoing ? laugh.score() : 0.0f, laugh.pitch(), whispering && active, silent,
				shape, lowShare, highShare);
	}

	/**
	 * Finds the "ha"s in a stretch of audio and judges whether they sound like a laugh.
	 *
	 * <p>Talking and laughing both make the level jump a few times a second. What
	 * gives a laugh away is that the "ha"s are evenly spaced, about equally loud
	 * and all the same vowel, while syllables of speech vary in all three.
	 */
	private static final class Laugh {
		private static final int MAX = 24;
		private final long[] at = new long[MAX];
		private final float[] peak = new float[MAX];
		private final float[] tone = new float[MAX];
		private final float[] pitch = new float[MAX];
		private int count;

		private boolean rising = true;
		private float max = -1.0f;
		private float min = 2.0f;
		private long maxAt;
		private float maxTone;
		private float maxPitch;

		void add(float level, float bright, float semitones, long time) {
			if (rising) {
				if (level > max) {
					max = level;
					maxAt = time;
					maxTone = bright;
					maxPitch = semitones;
				} else if (level < max - BURST_DELTA) {
					if (max >= BURST_FLOOR) {
						burst();
					}
					rising = false;
					min = level;
				}
			} else if (level < min) {
				min = level;
			} else if (level > min + BURST_DELTA) {
				rising = true;
				max = level;
				maxAt = time;
				maxTone = bright;
				maxPitch = semitones;
			}
		}

		private void burst() {
			if (count > 0 && maxAt - at[count - 1] > LAUGH_BREAK) {
				count = 0;
			}
			if (count == MAX) {
				System.arraycopy(at, 1, at, 0, MAX - 1);
				System.arraycopy(peak, 1, peak, 0, MAX - 1);
				System.arraycopy(tone, 1, tone, 0, MAX - 1);
				System.arraycopy(pitch, 1, pitch, 0, MAX - 1);
				count--;
			}
			at[count] = maxAt;
			peak[count] = max;
			tone[count] = maxTone;
			pitch[count] = maxPitch;
			count++;
		}

		float score() {
			// Fewer than this could just be a short sentence.
			if (count < 5) {
				return 0.0f;
			}
			// Spacing between "ha"s: laughs run at about 3 to 8 per second, very evenly.
			float mean = (at[count - 1] - at[0]) / (float) (count - 1) / 1.0e9f;
			if (mean < 0.1f || mean > 0.4f) {
				return 0.0f;
			}
			float spread = 0.0f;
			for (int i = 1; i < count; i++) {
				float d = (at[i] - at[i - 1]) / 1.0e9f - mean;
				spread += d * d;
			}
			float rhythm = ramp((float) Math.sqrt(spread / (count - 1)) / mean, 0.4f, 0.15f);
			float evenness = ramp(deviation(peak), 0.16f, 0.06f);
			float tones = mean(tone);
			float sameSound = tones <= 0.0f ? 0.0f : ramp(deviation(tone) / tones, 0.5f, 0.2f);
			return rhythm * (0.4f + 0.6f * evenness) * (0.4f + 0.6f * sameSound);
		}

		/** Average pitch of the bursts that had one, or NaN. */
		float pitch() {
			float sum = 0.0f;
			int n = 0;
			for (int i = 0; i < count; i++) {
				if (!Float.isNaN(pitch[i])) {
					sum += pitch[i];
					n++;
				}
			}
			return n > 0 ? sum / n : NO_PITCH;
		}

		private float mean(float[] values) {
			float sum = 0.0f;
			for (int i = 0; i < count; i++) {
				sum += values[i];
			}
			return sum / count;
		}

		private float deviation(float[] values) {
			float mean = mean(values);
			float sum = 0.0f;
			for (int i = 0; i < count; i++) {
				sum += (values[i] - mean) * (values[i] - mean);
			}
			return (float) Math.sqrt(sum / count);
		}

		/** 0 at {@code bad}, 1 at {@code good}, straight line between. */
		private static float ramp(float value, float bad, float good) {
			return Math.max(0.0f, Math.min(1.0f, (value - bad) / (good - bad)));
		}
	}
}
