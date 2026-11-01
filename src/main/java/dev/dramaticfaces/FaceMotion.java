package dev.dramaticfaces;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** Bridge for the mixin, so {@link Face} can stay package-private. */
public final class FaceMotion {
	private FaceMotion() {
	}

	public static void apply(AvatarRenderState state, ModelPart head) {
		if (state.isInvisible) {
			return;
		}
		Face face = Faces.get(state.id);
		if (face != null) {
			float strength = (float) FacesConfig.get().headMotion;
			head.xRot += face.pitch() * strength;
			head.yRot += face.yaw() * strength;
			head.zRot += face.roll() * strength;
		}
	}
}
