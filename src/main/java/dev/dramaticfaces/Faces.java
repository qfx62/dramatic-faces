package dev.dramaticfaces;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Every player we've heard or seen, and their animated faces. */
public final class Faces {
	/** Filled from the audio thread. */
	private static final Map<UUID, VoiceTrack> TRACKS = new ConcurrentHashMap<>();
	/** Only touched on the render thread. */
	private static final Map<UUID, Face> FACES = new HashMap<>();
	private static final Map<UUID, Watch> WATCHES = new HashMap<>();
	private static @Nullable ClientLevel lastLevel;

	/** Players this close look at whoever is talking. */
	private static final double LISTEN_RANGE = 10.0;
	/** A swelling creeper this close scares a player. */
	private static final double CREEPER_RANGE = 7.0;
	/** Players this close count as being together, for awkward silences. */
	private static final double GROUP_RANGE = 6.0;
	/** Nobody in the group has said anything for this long... */
	private static final float AWKWARD_AFTER = 12.0f;
	/** ...but someone did talk this recently, so it's a conversation that died. */
	private static final float CONVERSATION_WITHIN = 30.0f;

	/** Slow lookups (creepers, awkward silences), remembered between frames. */
	private static final class Watch {
		long nextScan;
		@Nullable Creeper creeper;
		long nextAwkwardCheck;
		long sensedAt;
		Face.@Nullable Senses senses;
	}

	private Faces() {
	}

	static void hear(UUID player, short[] audio, boolean whispering) {
		TRACKS.computeIfAbsent(player, id -> new VoiceTrack()).push(audio, whispering, System.nanoTime());
	}

	/** The up-to-date face of the player with this entity id, or null if there is nothing to show. */
	static @Nullable Face get(int entityId) {
		Minecraft minecraft = Minecraft.getInstance();
		FacesConfig config = FacesConfig.get();
		if (minecraft.level == null || !config.enabled) {
			return null;
		}
		if (minecraft.level != lastLevel) {
			// New world or server: forget everyone from the last one.
			lastLevel = minecraft.level;
			FACES.clear();
			WATCHES.clear();
		}
		Entity entity = minecraft.level.getEntity(entityId);
		if (!(entity instanceof Player player)) {
			return null;
		}
		if (player == minecraft.player && !config.animateSelf) {
			return null;
		}
		UUID id = player.getUUID();
		VoiceTrack track = TRACKS.computeIfAbsent(id, uuid -> new VoiceTrack());
		Face face = FACES.computeIfAbsent(id, uuid -> new Face(track));
		Watch watch = WATCHES.computeIfAbsent(id, uuid -> new Watch());
		long now = System.nanoTime();
		// The head motion and the face drawing both ask every frame; work it out once.
		if (watch.senses == null || now - watch.sensedAt > 5_000_000L) {
			watch.senses = sense(minecraft.level, player, watch, now);
			watch.sensedAt = now;
		}
		face.update(now, watch.senses);
		if (now >= watch.nextAwkwardCheck) {
			watch.nextAwkwardCheck = now + 500_000_000L;
			checkAwkward(minecraft.level, player, face);
		}
		return face.idle() ? null : face;
	}

	private static Face.Senses sense(ClientLevel level, Player player, Watch watch, long now) {
		boolean still = player.getDeltaMovement().horizontalDistanceSqr() < 1.0e-4 && !player.isFallFlying() && !player.isSwimming();
		float health = player.getHealth();
		boolean lowHealth = health > 0.0f && health <= player.getMaxHealth() * 0.3f;
		int using = 0;
		if (player.isUsingItem()) {
			ItemUseAnimation animation = player.getUseItem().getUseAnimation();
			using = animation == ItemUseAnimation.EAT ? 1 : animation == ItemUseAnimation.DRINK ? 2 : 0;
		}

		if (now >= watch.nextScan) {
			watch.nextScan = now + 100_000_000L;
			List<Creeper> creepers = level.getEntitiesOfClass(Creeper.class, player.getBoundingBox().inflate(CREEPER_RANGE),
					creeper -> creeper.getSwelling(1.0f) > 0.02f);
			watch.creeper = null;
			double nearest = Double.MAX_VALUE;
			for (Creeper creeper : creepers) {
				double d = creeper.distanceToSqr(player);
				if (d < nearest) {
					nearest = d;
					watch.creeper = creeper;
				}
			}
		}
		boolean scared = watch.creeper != null && watch.creeper.isAlive();

		// Something to look at: the creeper, otherwise the nearest player who's talking.
		Vec3 target = null;
		if (scared) {
			target = watch.creeper.getEyePosition();
		} else {
			double nearest = LISTEN_RANGE * LISTEN_RANGE;
			for (AbstractClientPlayer other : level.players()) {
				if (other == player) {
					continue;
				}
				VoiceTrack track = TRACKS.get(other.getUUID());
				double d = other.distanceToSqr(player);
				if (track != null && track.talking(now) && d < nearest) {
					nearest = d;
					target = other.getEyePosition();
				}
			}
		}
		boolean look = false;
		float lookYaw = 0.0f;
		float lookPitch = 0.0f;
		if (target != null) {
			Vec3 to = target.subtract(player.getEyePosition());
			float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90.0f;
			float pitch = (float) -(Mth.atan2(to.y, to.horizontalDistance()) * Mth.RAD_TO_DEG);
			lookYaw = Mth.wrapDegrees(yaw - player.getYHeadRot());
			lookPitch = pitch - player.getXRot();
			// Only what's roughly in front; nobody turns their head all the way around.
			look = Math.abs(lookYaw) < 110.0f;
		}
		return new Face.Senses(still, look, lookYaw, lookPitch, player.hurtTime, lowHealth, scared, using);
	}

	/** Players standing together who all went quiet after talking: that's an awkward silence. */
	private static void checkAwkward(ClientLevel level, Player player, Face face) {
		if (!FacesConfig.get().awkward) {
			return;
		}
		// How long since anyone in the group said something.
		float silence = face.silentFor();
		int others = 0;
		for (AbstractClientPlayer other : level.players()) {
			if (other == player || other.distanceToSqr(player) > GROUP_RANGE * GROUP_RANGE) {
				continue;
			}
			others++;
			Face theirs = FACES.get(other.getUUID());
			silence = Math.min(silence, theirs == null ? Float.MAX_VALUE : theirs.silentFor());
		}
		if (others > 0 && silence >= AWKWARD_AFTER && silence <= CONVERSATION_WITHIN) {
			face.startAwkward();
		}
	}
}
