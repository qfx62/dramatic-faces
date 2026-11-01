package dev.dramaticfaces.mixin;

import dev.dramaticfaces.FaceLayer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives every player renderer the face layer. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@SuppressWarnings("unchecked")
	@Inject(method = "<init>", at = @At("TAIL"))
	private void dramaticfaces$addFaceLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
		RenderLayerParent<AvatarRenderState, PlayerModel> parent = (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) this;
		((LivingEntityRendererInvoker) (Object) this).dramaticfaces$addLayer(new FaceLayer(parent));
	}
}
