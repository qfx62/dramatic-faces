package dev.dramaticfaces;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

/** The player's settings, saved in config/dramaticfaces.json. */
public final class FacesConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static FacesConfig instance;

	/** Everything on or off. */
	public boolean enabled = true;
	/** Your own face reacts to your microphone (others with the mod see it too). */
	public boolean animateSelf = true;
	public boolean showMouth = true;
	public boolean showBrows = true;
	/** Eyes that blink, look around and change with the mood. */
	public boolean eyes = true;
	/** Eyes and head turn toward whoever is talking nearby. */
	public boolean lookAt = true;
	/** Mouth shapes follow the sounds ("a", "o", "e", "s", "m"). */
	public boolean lipSync = true;
	/** Faces react to the game: getting hit, low health, creepers, eating and drinking. */
	public boolean reactions = true;
	/** Sweat and darting eyes when a conversation dies. */
	public boolean awkward = true;
	public boolean particles = true;
	/** How much the head moves, 0 to 2 (0% to 200%). */
	public double headMotion = 1.0;
	/** Moves the drawn mouth up or down by whole skin pixels, for skins whose mouth isn't on the usual row. */
	public int mouthOffset = 0;
	public Sensitivity shoutSensitivity = Sensitivity.NORMAL;
	public boolean shouting = true;
	public boolean laughing = true;
	public boolean panic = true;
	public boolean whispering = true;
	public boolean sad = true;
	/** Z's float up from players who have been silent for a while. */
	public boolean sleepy = true;

	public enum Sensitivity {
		LOW(0.28f), NORMAL(0.2f), HIGH(0.13f);

		/** How much louder than their usual speech someone must be to count as shouting. */
		final float margin;

		Sensitivity(float margin) {
			this.margin = margin;
		}
	}

	public static FacesConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("dramaticfaces.json");
	}

	private static FacesConfig load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				FacesConfig loaded = GSON.fromJson(reader, FacesConfig.class);
				if (loaded != null) {
					if (loaded.shoutSensitivity == null) {
						loaded.shoutSensitivity = Sensitivity.NORMAL;
					}
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				// A broken file just means default settings.
			}
		}
		return new FacesConfig();
	}

	public void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			// Not being able to save settings shouldn't crash the game.
		}
	}
}
