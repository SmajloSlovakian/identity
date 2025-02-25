package draylar.identity.mixin;

import draylar.identity.Identity;
import draylar.identity.cca.UnlockedIdentitiesComponent;
import draylar.identity.registry.Components;
import draylar.identity.registry.EntityTags;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.passive.BatEntity;
import net.minecraft.entity.passive.DolphinEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {

    @Shadow protected abstract int getNextAirOnLand(int air);

    @Shadow public abstract boolean hasStatusEffect(StatusEffect effect);

    protected LivingEntityMixin(EntityType<?> type, World world) {
        super(type, world);
    }

    @Inject(
            method = "onDeath",
            at = @At("HEAD")
    )
    private void onDeath(DamageSource source, CallbackInfo ci) {
        Entity attacker = source.getAttacker();
        EntityType<?> thisType = this.getType();

        // check if attacker is a player to grant identity
        if(attacker instanceof PlayerEntity) {
            boolean isNew = false;
            UnlockedIdentitiesComponent unlocked = Components.UNLOCKED_IDENTITIES.get(attacker);
            boolean result = unlocked.unlock(thisType);

            // ensure type has not already been unlocked
            if(result && !unlocked.has(thisType)) {

                // send unlock message to player if they aren't in creative and the config option is on
                if(Identity.CONFIG.overlayIdentityUnlocks && !((PlayerEntity) attacker).isCreative()) {
                    ((PlayerEntity) attacker).sendMessage(
                            new TranslatableText(
                                    "identity.unlock_entity",
                                    new TranslatableText(thisType.getTranslationKey())
                            ), true
                    );
                }

                isNew = true;
            }

            // force-morph player into new type
            Entity instanced = thisType.create(attacker.world);
            if(instanced instanceof LivingEntity) {
                if(Identity.CONFIG.forceChangeNew && isNew) {
                    Components.CURRENT_IDENTITY.get(attacker).setIdentity((LivingEntity) instanced);
                } else if (Identity.CONFIG.forceChangeAlways) {
                    Components.CURRENT_IDENTITY.get(attacker).setIdentity((LivingEntity) instanced);
                }
            }
        }
    }

    @Redirect(
            method = "baseTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;setAir(I)V", ordinal = 2)
    )
    private void cancelAirIncrement(LivingEntity livingEntity, int air) {
        // Aquatic creatures should not regenerate breath on land
        if ((Object) this instanceof PlayerEntity) {
            LivingEntity identity = Components.CURRENT_IDENTITY.get(this).getIdentity();

            if (identity != null) {
                if (Identity.isAquatic(identity)) {
                    return;
                }
            }
        }

        this.setAir(this.getNextAirOnLand(this.getAir()));
    }

    @Redirect(
            method = "travel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;hasStatusEffect(Lnet/minecraft/entity/effect/StatusEffect;)Z", ordinal = 0)
    )
    private boolean slowFall(LivingEntity livingEntity, StatusEffect effect) {
        if((Object) this instanceof PlayerEntity) {
            LivingEntity identity = Components.CURRENT_IDENTITY.get(this).getIdentity();

            if (identity != null) {
                if (!this.isSneaking() && EntityTags.SLOW_FALLING.contains(identity.getType())) {
                    return true;
                }
            }
        }

        return this.hasStatusEffect(StatusEffects.SLOW_FALLING);
    }

    @Redirect(
            method = "travel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;hasStatusEffect(Lnet/minecraft/entity/effect/StatusEffect;)Z", ordinal = 1)
    )
    private boolean applyWaterCreatureSwimSpeedBoost(LivingEntity livingEntity, StatusEffect effect) {
        if((Object) this instanceof PlayerEntity) {
            LivingEntity identity = Components.CURRENT_IDENTITY.get(this).getIdentity();

            // Apply 'Dolphin's Grace' status effect benefits if the player's Identity is a water creature
            if (identity instanceof WaterCreatureEntity) {
                return true;
            }
        }

        return this.hasStatusEffect(StatusEffects.DOLPHINS_GRACE);
    }

    @Inject(
            method = "handleFallDamage",
            at = @At(value = "HEAD"),
            cancellable = true
    )
    private void handleFallDamage(float fallDistance, float damageMultiplier, CallbackInfoReturnable<Boolean> cir) {
        if((Object) this instanceof PlayerEntity) {
            LivingEntity identity = Components.CURRENT_IDENTITY.get(this).getIdentity();

            if (identity != null) {
                boolean takesFallDamage = identity.handleFallDamage(fallDistance, damageMultiplier);
                int damageAmount = ((LivingEntityAccessor) identity).callComputeFallDamage(fallDistance, damageMultiplier);

                if (takesFallDamage && damageAmount > 0) {
                    this.playSound(((LivingEntityAccessor) identity).callGetFallSound(damageAmount), 1.0F, 1.0F);
                    ((LivingEntityAccessor) identity).callPlayBlockFallSound();
                    this.damage(DamageSource.FALL, (float) damageAmount);
                    cir.setReturnValue(true);
                } else {
                    cir.setReturnValue(false);
                }
            }
        }
    }

    @Inject(
            method = "hasStatusEffect",
            at = @At("HEAD"),
            cancellable = true
    )
    private void returnHasNightVision(StatusEffect effect, CallbackInfoReturnable<Boolean> cir) {
        if((Object) this instanceof PlayerEntity) {
            if (effect.equals(StatusEffects.NIGHT_VISION)) {
                LivingEntity identity = Components.CURRENT_IDENTITY.get(this).getIdentity();

                // Apply 'Night Vision' status effect to player if they are a Bat
                if (identity instanceof BatEntity) {
                    cir.setReturnValue(true);
                }
            }
        }
    }

    @Inject(
            method = "getStatusEffect",
            at = @At("HEAD"),
            cancellable = true
    )
    private void returnNightVisionInstance(StatusEffect effect, CallbackInfoReturnable<StatusEffectInstance> cir) {
        if((Object) this instanceof PlayerEntity) {
            if (effect.equals(StatusEffects.NIGHT_VISION)) {
                LivingEntity identity = Components.CURRENT_IDENTITY.get(this).getIdentity();

                // Apply 'Night Vision' status effect to player if they are a Bat
                if (identity instanceof BatEntity) {
                    cir.setReturnValue(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 100000, 0, false, false));
                }
            }
        }
    }

    @Inject(
            method = "getMaxHealth",
            at = @At("HEAD"),
            cancellable = true
    )
    private void modifyMaxHealth(CallbackInfoReturnable<Float> cir) {
        if(Identity.CONFIG.scalingHealth) {
            if ((Object) this instanceof PlayerEntity) {
                LivingEntity identity = Components.CURRENT_IDENTITY.get(this).getIdentity();

                if (identity != null) {
                    cir.setReturnValue(identity.getMaxHealth());
                }
            }
        }
    }

    @Inject(method = "hurtByWater", at = @At("HEAD"), cancellable = true)
    protected void identity_hurtByWater(CallbackInfoReturnable<Boolean> cir) {
        // NO-OP
    }

    @Inject(method = "canBreatheInWater", at = @At("HEAD"), cancellable = true)
    protected void identity_canBreatheInWater(CallbackInfoReturnable<Boolean> cir) {
        // NO-OP
    }

    @Environment(EnvType.CLIENT)
    @Inject(method = "setNearbySongPlaying", at = @At("RETURN"))
    protected void identity_setNearbySongPlaying(BlockPos songPosition, boolean playing, CallbackInfo ci) {
        // NO-OP
    }

    @Inject(method = "isUndead", at = @At("HEAD"), cancellable = true)
    protected void identity_isUndead(CallbackInfoReturnable<Boolean> cir) {
        // NO-OP
    }

    @Inject(method = "canWalkOnFluid", at = @At("HEAD"), cancellable = true)
    protected void identity_canWalkOnFluid(Fluid fluid, CallbackInfoReturnable<Boolean> cir) {
        // NO-OP
    }

    @Inject(
            method = "isClimbing",
            at = @At("HEAD"),
            cancellable = true
    )
    protected void identity_allowSpiderClimbing(CallbackInfoReturnable<Boolean> cir) {
        // NO-OP
    }
}
