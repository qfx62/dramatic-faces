package dev.dramaticfaces;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Hooks into Simple Voice Chat. Only reads the audio to measure how loud it is;
 * it never changes, stores or sends anything.
 */
public class FacesVoicePlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "dramaticfaces";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		// Other players talking near you (or in your group).
		registration.registerEvent(ClientReceiveSoundEvent.class, event -> {
			boolean whispering = event instanceof ClientReceiveSoundEvent.EntitySound entity && entity.isWhispering();
			Faces.hear(event.getId(), event.getRawAudio(), whispering);
		});
		// Your own microphone, so your face moves too (visible in third person and to others with the mod).
		registration.registerEvent(ClientSoundEvent.class, event -> {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				Faces.hear(player.getUUID(), event.getRawAudio(), event.isWhispering());
			}
		});
	}
}
