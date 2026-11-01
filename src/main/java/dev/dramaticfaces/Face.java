package dev.dramaticfaces;

import java.util.Locale;
import java.util.Random;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * The animated face of one player: which emotion it shows, how far the mouth
 * is open and how the head moves. Lives on the render thread.
 */
final class Face {
	enum Emotion {
		/** Not talking. */
		NEUTRAL,
		/** Normal speech: small nods and tilts. */
		TALK,
		/** Whispering (or the voice chat whisper key): leans in, tiny mouth. */
		WHISPER,
		/** Much louder than this player usually talks: chin up, angry brows. */
		SHOUT,
		/** Rapid bursts of sound, "ha ha ha": head back, grin, shaking. */
		LAUGH,
		/** Loud and high pitched: head shaking, mouth wide open, brows up. */
		PANIC,
		/** Quieter, lower and flatter than this player usually talks: head down, frown, tears. */
		SAD
	}

	/** Reactions to what happens in the game, no voice needed. */
	enum Reaction {
		NONE,
		/** Just took damage: eyes squeezed shut, teeth clenched, head jerks back. */
		HURT,
		/** A creeper is about to blow up nearby: wide eyes, mouth open, trembling. */
		SCARED,
		/** Eating: chewing. */
		EAT,
		/** Drinking: head back, eyes half closed. */
		DRINK,
		/** Nearby players stopped talking and nobody says anything: sweat, eyes darting. */
		AWKWARD,
		/** Low on health: worried brows, wobbly mouth. */
		WORRIED
	}

	/** Everything the face can show, from the voice and the game together; what gets drawn. */
	enum Expression {
		NEUTRAL, TALK, WHISPER, SHOUT, LAUGH, PANIC, SAD, HURT, SCARED, EAT, DRINK, AWKWARD, WORRIED
	}

	/** How the eyes are drawn. */
	enum EyeStyle {
		NORMAL,
		/** Bigger, white all around the pupil. */
		WIDE,
		/** Closed and curved up, "^ ^". */
		HAPPY,
		/** Lids pulled down toward the nose. */
		SQUINT,
		/** Lids drooping at the outer corners. */
		DROOPY,
		/** Squeezed shut, "> <". */
		SHUT
	}

	/**
	 * What's going on around this player, gathered by {@link Faces} each frame.
	 *
	 * @param look whether there is something to look at
	 * @param lookYaw how far to the side it is, in degrees; positive is to the player's right
	 * @param lookPitch how far below where the player looks it is, in degrees
	 * @param hurtTime the player's hurt timer, which jumps up when they take damage
	 * @param using 1 while eating, 2 while drinking, 0 otherwise
	 */
	record Senses(boolean still, boolean look, float lookYaw, float lookPitch, int hurtTime, boolean lowHealth,
			boolean scared, int using) {
	}

	private static final Emotion[] EMOTIONS = Emotion.values();
	private static final Reaction[] REACTIONS = Reaction.values();
	static final boolean DEBUG = FabricLoader.getInstance().isDevelopmentEnvironment();

	private final VoiceTrack track;
	private long lastNanos;
	private float time;
	/** Smoothed loudness, follows every syllable. */
	private float envelope;
	/** Smoothed laugh score, so one lucky run of syllables doesn't make a laugh. */
	private float laughter;
	/** Builds up while the voice sounds down, so one low sentence isn't enough to be sad. */
	private float gloom;
	/** Builds up while the voice is well above normal, so a loud word or two isn't shouting. */
	private float shouting;
	/** Builds up while the voice has no pitch, so a breathy sound or two isn't whispering. */
	private float hushed;
	/** Seconds of this player's speech heard so far; no emotions until we know how they normally sound. */
	private float heard;
	private float debugTimer;
	/** How loud this player normally talks, learned over time. */
	private float baseline = 0.5f;
	/** This player's normal speaking pitch in semitones above 100 Hz, learned over time; NaN until heard. */
	private float basePitch = Float.NaN;
	private Emotion emotion = Emotion.NEUTRAL;
	private Emotion pending = Emotion.NEUTRAL;
	private float pendingFor;
	private float heldFor;
	private final float[] weights = new float[EMOTIONS.length];

	private float pitch;
	private float yaw;
	private float roll;
	private float mouth;
	/** Seconds since this player last made a sound. */
	private float silentFor;
	/** Standing still and quiet for a long time: Z's float up. */
	private boolean sleepy;
	private float sleepyFor;

	private final Random random = new Random();
	private Senses senses = new Senses(false, false, 0.0f, 0.0f, 0, false, false, 0);
	private Reaction reaction = Reaction.NONE;
	private final float[] reactionWeights = new float[REACTIONS.length];
	private int lastHurtTime;
	private float hurtLeft;
	private float awkwardLeft;
	private float awkwardCooldown;

	private VoiceTrack.Shape shape = VoiceTrack.Shape.CLOSED;
	private float shapeHeld;

	private float eyeOpen = 1.0f;
	private float blinkUntil;
	private float nextBlink;
	private float lookX;
	private float lookY;
	private float wanderX;
	private float wanderY;
	private float nextWander;
	/** Head turn toward what the player looks at, in radians. */
	private float turnYaw;
	private float turnPitch;

	Face(VoiceTrack track) {
		this.track = track;
		weights[Emotion.NEUTRAL.ordinal()] = 1.0f;
		reactionWeights[Reaction.NONE.ordinal()] = 1.0f;
		nextBlink = 1.0f + random.nextFloat() * 3.0f;
	}

	/** Silent for this long while standing still counts as dozing off. */
	private static final float SLEEPY_AFTER = 45.0f;
	/** Seconds of speech to hear before showing any emotions. */
	private static final float CALIBRATE = 15.0f;
	/** Never shouting below this level, however quiet this player normally is. */
	private static final float SHOUT_FLOOR = 0.62f;

	/** Brings the face up to date; calling it more than once per frame is harmless. */
	void update(long now, Senses senses) {
		this.senses = senses;
		update(now);
		VoiceTrack.Snapshot voice = track.snapshot(now);
		silentFor = voice.silentNanos() / 1.0e9f;
		boolean wasSleepy = sleepy;
		sleepy = senses.still() && silentFor > SLEEPY_AFTER;
		if (sleepy && !wasSleepy) {
			sleepyFor = 0.0f;
		}
	}

	/** Starts the awkward silence look, unless it was shown recently. */
	void startAwkward() {
		if (awkwardCooldown <= 0.0f) {
			awkwardLeft = 5.0f;
			awkwardCooldown = 60.0f;
		}
	}

	/** Seconds since this player last said anything, or a very large number if never. */
	float silentFor() {
		return silentFor;
	}

	private void update(long now) {
		float dt = lastNanos == 0L ? 0.0f : (now - lastNanos) / 1.0e9f;
		if (lastNanos != 0L && dt < 0.001f) {
			return;
		}
		dt = Math.min(dt, 0.1f);
		lastNanos = now;
		time += dt;
		sleepyFor += dt;

		VoiceTrack.Snapshot voice = track.snapshot(now);
		float rate = voice.level() > envelope ? 30.0f : 14.0f;
		envelope += (voice.level() - envelope) * (1.0f - (float) Math.exp(-dt * rate));
		// A laugh only builds up while sound is coming in, not from the echo of the last sentence.
		float laugh = laughPitched(voice);
		if (laugh <= laughter || voice.active()) {
			laughter += (laugh - laughter) * (1.0f - (float) Math.exp(-dt * (laugh > laughter ? 8.0f : 3.0f)));
		}
		if (voice.active()) {
			// Evidence fills up over about a second of the real thing and drains quickly, so moments don't count.
			shouting = Mth.clamp(shouting + (soundsLoud(voice) ? dt / 0.9f : -dt / 0.4f), 0.0f, 1.0f);
			hushed = Mth.clamp(hushed + (soundsWhispered(voice) ? dt / 0.8f : -dt / 0.4f), 0.0f, 1.0f);
			if (voice.speech() >= 15) {
				gloom = Mth.clamp(gloom + (soundsSad(voice) ? dt / 3.5f : -dt / 0.6f), 0.0f, 1.0f);
			}
		}

		Emotion target = classify(voice);
		if (voice.active() && voice.speech() >= 8 && emotion != Emotion.LAUGH && emotion != Emotion.WHISPER) {
			heard += dt;
			// Slowly learn this player's normal volume and pitch, so "loud" and "low" mean that for them.
			// Learning from all their speech, not just what already counts as normal, so it can't get stuck too low.
			float learn = 1.0f - (float) Math.exp(-dt / (heard < CALIBRATE ? 4.0f : 30.0f));
			baseline += (voice.loud() - baseline) * learn;
			if (!Float.isNaN(voice.pitch())) {
				basePitch = Float.isNaN(basePitch) ? voice.pitch() : basePitch + (voice.pitch() - basePitch) * learn;
			}
		}
		heldFor += dt;
		if (target == emotion) {
			pendingFor = 0.0f;
		} else {
			pendingFor = target == pending ? pendingFor + dt : dt;
			pending = target;
			float hold = switch (emotion) {
				case SHOUT, LAUGH, PANIC, SAD -> 0.7f;
				default -> 0.15f;
			};
			if (pendingFor > 0.12f && heldFor > hold) {
				emotion = target;
				heldFor = 0.0f;
				pendingFor = 0.0f;
			}
		}
		float blend = 1.0f - (float) Math.exp(-dt * 8.0f);
		for (Emotion e : EMOTIONS) {
			int i = e.ordinal();
			weights[i] += ((e == emotion ? 1.0f : 0.0f) - weights[i]) * blend;
		}

		float open = (envelope - 0.12f) / Math.max(0.15f, Math.max(baseline, 0.35f) + 0.2f - 0.12f);
		mouth = Mth.clamp(open, 0.0f, 1.0f);
		shapeHeld += dt;
		if (voice.shape() != shape && shapeHeld > 0.07f) {
			shape = voice.shape();
			shapeHeld = 0.0f;
		}
		react(dt);
		eyes(dt);
		move();
		debug(voice, dt);
	}

	private void react(float dt) {
		FacesConfig config = FacesConfig.get();
		if (senses.hurtTime() > lastHurtTime) {
			hurtLeft = 0.6f;
		}
		lastHurtTime = senses.hurtTime();
		hurtLeft = Math.max(0.0f, hurtLeft - dt);
		awkwardLeft = Math.max(0.0f, awkwardLeft - dt);
		awkwardCooldown = Math.max(0.0f, awkwardCooldown - dt);
		if (silentFor < 1.0f) {
			// Someone said something: not awkward anymore.
			awkwardLeft = 0.0f;
		}

		reaction = Reaction.NONE;
		if (config.reactions) {
			if (hurtLeft > 0.0f) {
				reaction = Reaction.HURT;
			} else if (senses.scared()) {
				reaction = Reaction.SCARED;
			} else if (senses.using() == 1) {
				reaction = Reaction.EAT;
			} else if (senses.using() == 2) {
				reaction = Reaction.DRINK;
			} else if (senses.lowHealth()) {
				reaction = Reaction.WORRIED;
			}
		}
		if (reaction == Reaction.NONE && config.awkward && awkwardLeft > 0.0f) {
			reaction = Reaction.AWKWARD;
		}
		float blend = 1.0f - (float) Math.exp(-dt * (reaction == Reaction.HURT ? 25.0f : 8.0f));
		for (Reaction r : REACTIONS) {
			int i = r.ordinal();
			reactionWeights[i] += ((r == reaction ? 1.0f : 0.0f) - reactionWeights[i]) * blend;
		}
	}

	private void eyes(float dt) {
		Expression expression = expression();
		float open = switch (expression) {
			case WHISPER -> 0.6f;
			case DRINK -> 0.3f;
			case EAT -> 0.75f;
			default -> 1.0f;
		};
		if (sleepy && FacesConfig.get().sleepy) {
			open *= Math.max(0.0f, 1.0f - sleepyFor / 3.0f);
		}
		if (time >= nextBlink) {
			blinkUntil = time + 0.12f;
			// Now and then a quick double blink.
			nextBlink = time + (random.nextFloat() < 0.15f ? 0.3f : 2.0f + random.nextFloat() * 4.0f);
		}
		if (time < blinkUntil) {
			open = 0.0f;
		}
		eyeOpen += (open - eyeOpen) * (1.0f - (float) Math.exp(-dt * 30.0f));

		// Where to look: at the thing that matters, otherwise little glances around.
		float targetX;
		float targetY;
		float targetYaw = 0.0f;
		float targetPitch = 0.0f;
		if (reaction == Reaction.AWKWARD) {
			targetX = Mth.sin(time * 4.5f) > 0.0f ? 0.9f : -0.9f;
			targetY = 0.0f;
		} else if (senses.look() && FacesConfig.get().lookAt) {
			// The player's right is the face's left as you look at it.
			targetX = Mth.clamp(-senses.lookYaw() / 35.0f, -1.0f, 1.0f);
			targetY = Mth.clamp(senses.lookPitch() / 30.0f, -1.0f, 1.0f);
			targetYaw = Mth.clamp(senses.lookYaw() * Mth.DEG_TO_RAD * 0.4f, -0.5f, 0.5f);
			targetPitch = Mth.clamp(senses.lookPitch() * Mth.DEG_TO_RAD * 0.3f, -0.3f, 0.3f);
		} else {
			if (time >= nextWander) {
				boolean ahead = random.nextFloat() < 0.6f;
				wanderX = ahead ? 0.0f : (random.nextFloat() * 2.0f - 1.0f) * 0.8f;
				wanderY = ahead ? 0.0f : (random.nextFloat() * 2.0f - 1.0f) * 0.4f;
				nextWander = time + 1.0f + random.nextFloat() * 3.0f;
			}
			targetX = wanderX;
			targetY = wanderY;
		}
		// Eyes jump quickly, the head follows slowly.
		float jump = 1.0f - (float) Math.exp(-dt * 18.0f);
		lookX += (targetX - lookX) * jump;
		lookY += (targetY - lookY) * jump;
		float follow = 1.0f - (float) Math.exp(-dt * 4.0f);
		turnYaw += (targetYaw - turnYaw) * follow;
		turnPitch += (targetPitch - turnPitch) * follow;
	}

	/** What to draw right now, from the voice and the game together. */
	Expression expression() {
		if (reaction == Reaction.HURT) {
			return Expression.HURT;
		}
		if (reaction == Reaction.SCARED) {
			return Expression.SCARED;
		}
		switch (emotion) {
			case WHISPER -> {
				return Expression.WHISPER;
			}
			case SHOUT -> {
				return Expression.SHOUT;
			}
			case LAUGH -> {
				return Expression.LAUGH;
			}
			case PANIC -> {
				return Expression.PANIC;
			}
			case SAD -> {
				return Expression.SAD;
			}
			default -> {
			}
		}
		boolean talking = emotion == Emotion.TALK;
		return switch (reaction) {
			case EAT -> Expression.EAT;
			case DRINK -> Expression.DRINK;
			// Talking wins over these two; the eyes and brows still show it.
			case AWKWARD -> talking ? Expression.TALK : Expression.AWKWARD;
			case WORRIED -> talking ? Expression.TALK : Expression.WORRIED;
			default -> talking ? Expression.TALK : Expression.NEUTRAL;
		};
	}

	EyeStyle eyeStyle() {
		return switch (expression()) {
			case LAUGH -> EyeStyle.HAPPY;
			case PANIC, SCARED -> EyeStyle.WIDE;
			case SHOUT -> EyeStyle.SQUINT;
			case SAD, WORRIED -> EyeStyle.DROOPY;
			case HURT -> EyeStyle.SHUT;
			default -> reaction == Reaction.WORRIED ? EyeStyle.DROOPY : EyeStyle.NORMAL;
		};
	}

	/** In the dev game, prints what the face hears so the thresholds can be tuned. */
	private void debug(VoiceTrack.Snapshot voice, float dt) {
		if (!DEBUG || !voice.active()) {
			return;
		}
		debugTimer += dt;
		if (debugTimer < 0.25f) {
			return;
		}
		debugTimer = 0.0f;
		// println, not printf: only that one ends up in the game's log file.
		System.out.println(String.format(Locale.ROOT, "[Faces] loud %.2f/%.2f pitch %+.1f/%+.1f spread %.1f voiced %.2f | bursts %d laugh %.2f hapitch %+.1f (%.2f) shout %.2f gloom %.2f hush %.2f heard %.0f | %s low %.2f high %.2f -> %s",
			voice.loud(), baseline, voice.pitch(), basePitch, voice.spread(), voice.voicing(),
			voice.bursts(), voice.laugh(), voice.laughPitch(), laughter, shouting, gloom, hushed, heard,
			voice.shape(), voice.low(), voice.high(), emotion));
	}

	/** Semitones above this player's normal pitch, or NaN while either is unknown. */
	private float above(float semitones) {
		return semitones - basePitch;
	}

	/** The laugh score, held back unless the "ha"s are higher than this player's normal voice, as laughs nearly always are. */
	private float laughPitched(VoiceTrack.Snapshot voice) {
		float raise = above(voice.laughPitch());
		float factor = Float.isNaN(raise) ? 0.6f : 0.4f + 0.6f * Mth.clamp((raise - 1.0f) / 3.0f, 0.0f, 1.0f);
		return voice.laugh() * factor;
	}

	/** The volume this player has to go over to count as shouting. */
	private float shoutLine() {
		float line = Math.max(baseline + FacesConfig.get().shoutSensitivity.margin, SHOUT_FLOOR);
		// Once shouting, it takes a clear drop in volume to stop.
		return emotion == Emotion.SHOUT || emotion == Emotion.PANIC ? line - 0.05f : line;
	}

	private boolean soundsLoud(VoiceTrack.Snapshot voice) {
		return voice.speech() >= 15 && voice.loud() > shoutLine();
	}

	/** Quieter, lower and flatter than usual, but still a real voice (not whispering). */
	private boolean soundsSad(VoiceTrack.Snapshot voice) {
		float low = above(voice.pitch());
		return !Float.isNaN(low) && low < -3.0f
				&& voice.loud() < baseline - 0.08f
				&& voice.spread() < 2.5f
				&& voice.voicing() > 0.4f;
	}

	/** Breath without a voice: sound coming in, but almost none of it has a pitch. */
	private boolean soundsWhispered(VoiceTrack.Snapshot voice) {
		return voice.speech() >= 10 && voice.voicing() < 0.2f && voice.loud() < baseline - 0.05f;
	}

	private Emotion classify(VoiceTrack.Snapshot voice) {
		if (!voice.active()) {
			// Short pauses between words still count as talking.
			return voice.silentNanos() < 350_000_000L ? emotion : Emotion.NEUTRAL;
		}
		FacesConfig config = FacesConfig.get();
		if (config.whispering && voice.whispering()) {
			return Emotion.WHISPER;
		}
		if (heard < CALIBRATE) {
			return Emotion.TALK;
		}
		if (config.whispering && (hushed >= 1.0f || emotion == Emotion.WHISPER && hushed > 0.4f)) {
			return Emotion.WHISPER;
		}
		// Laughs come first: a big laugh is loud too, but it isn't shouting.
		if (config.laughing && (laughter > 0.5f || emotion == Emotion.LAUGH && laughter > 0.25f)) {
			return Emotion.LAUGH;
		}
		boolean wasLoud = emotion == Emotion.SHOUT || emotion == Emotion.PANIC;
		if (shouting >= 1.0f || wasLoud && shouting > 0.3f) {
			// Speech goes up and down 5 to 10 semitones all the time; a scream goes far beyond that.
			float raise = above(voice.pitch());
			if (config.panic && (emotion == Emotion.PANIC || !Float.isNaN(raise) && raise > 9.0f)) {
				return Emotion.PANIC;
			}
			if (config.shouting) {
				return Emotion.SHOUT;
			}
		}
		if (config.sad && (gloom >= 1.0f || emotion == Emotion.SAD && gloom > 0.4f)) {
			return Emotion.SAD;
		}
		return Emotion.TALK;
	}

	/** Head offsets, mixed from every emotion by how strongly it shows right now. */
	private void move() {
		float t = time;
		float e = envelope;
		pitch = 0.0f;
		yaw = 0.0f;
		roll = 0.0f;

		float talk = weight(Emotion.TALK);
		pitch += talk * (0.07f * e * Mth.sin(t * 6.3f) - 0.04f * e);
		yaw += talk * 0.05f * Mth.sin(t * 1.7f);
		roll += talk * 0.04f * Mth.sin(t * 2.3f);

		float whisper = weight(Emotion.WHISPER);
		pitch += whisper * (0.18f + 0.03f * Mth.sin(t * 5.0f));
		roll += whisper * 0.08f;

		float shout = weight(Emotion.SHOUT);
		pitch += shout * (-0.12f - 0.12f * e);
		yaw += shout * 0.04f * e * Mth.sin(t * 28.0f);

		float laugh = weight(Emotion.LAUGH);
		pitch += laugh * (-0.22f + 0.08f * Mth.sin(t * 13.0f));
		roll += laugh * 0.10f * Mth.sin(t * 6.5f);

		float panic = weight(Emotion.PANIC);
		pitch += panic * -0.1f;
		yaw += panic * 0.16f * Mth.sin(t * 22.0f);
		roll += panic * 0.06f * Mth.sin(t * 17.0f);

		float sad = weight(Emotion.SAD);
		pitch += sad * (0.3f + 0.03f * Mth.sin(t * 1.1f));
		yaw += sad * 0.05f * Mth.sin(t * 0.7f);
		roll += sad * (0.07f + 0.03f * Mth.sin(t * 0.9f));

		float hurt = weight(Reaction.HURT);
		float flinch = hurtLeft / 0.6f;
		pitch += hurt * -0.25f * flinch;
		roll += hurt * 0.12f * flinch * Mth.sin(t * 30.0f);

		float scared = weight(Reaction.SCARED);
		pitch += scared * -0.12f;
		yaw += scared * 0.04f * Mth.sin(t * 35.0f);

		float eat = weight(Reaction.EAT);
		pitch += eat * (0.06f + 0.05f * Mth.sin(t * 9.0f));

		float drink = weight(Reaction.DRINK);
		pitch += drink * -0.35f;

		float awkward = weight(Reaction.AWKWARD);
		pitch += awkward * 0.06f;
		yaw += awkward * 0.12f * Mth.sin(t * 1.7f);

		float worried = weight(Reaction.WORRIED);
		pitch += worried * 0.08f;
		yaw += worried * 0.05f * Mth.sin(t * 0.9f);

		yaw += turnYaw;
		pitch += turnPitch;

		if (sleepy && FacesConfig.get().sleepy) {
			// Slowly nodding off.
			float doze = Math.min(1.0f, sleepyFor / 2.0f);
			pitch += doze * (0.22f + 0.05f * Mth.sin(t * 0.8f));
			roll += doze * 0.06f;
		}
	}

	float weight(Emotion e) {
		return weights[e.ordinal()];
	}

	float weight(Reaction r) {
		return reactionWeights[r.ordinal()];
	}

	Reaction reaction() {
		return reaction;
	}

	VoiceTrack.Shape shape() {
		return shape;
	}

	/** 1 = eyes fully open, 0 = closed. */
	float eyeOpen() {
		return eyeOpen;
	}

	/** Where the eyes look, -1 (left as you look at the face) to 1. */
	float lookX() {
		return lookX;
	}

	/** -1 up to 1 down. */
	float lookY() {
		return lookY;
	}

	Emotion emotion() {
		return emotion;
	}

	float mouth() {
		return mouth;
	}

	float time() {
		return time;
	}

	float pitch() {
		return pitch;
	}

	float yaw() {
		return yaw;
	}

	float roll() {
		return roll;
	}

	boolean sleepy() {
		return sleepy;
	}

	/** Seconds since this player became sleepy. */
	float sleepyFor() {
		return sleepyFor;
	}

	/** Fully back to rest with nothing to draw or move (eyes off, quiet, nothing happening). */
	boolean idle() {
		return !FacesConfig.get().eyes && emotion == Emotion.NEUTRAL && weight(Emotion.NEUTRAL) > 0.99f
				&& mouth < 0.01f && !sleepy && reaction == Reaction.NONE && weight(Reaction.NONE) > 0.99f
				&& Math.abs(turnYaw) < 0.01f && Math.abs(turnPitch) < 0.01f;
	}
}
