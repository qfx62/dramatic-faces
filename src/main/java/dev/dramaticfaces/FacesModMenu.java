package dev.dramaticfaces;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds the settings button to Dramatic Faces in Mod Menu. Only loaded when Mod Menu is installed. */
public class FacesModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return FacesSettingsScreen::new;
	}
}
