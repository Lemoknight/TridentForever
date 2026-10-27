package com.example.tridentforever;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Mod(TridentForever.MODID)
@EventBusSubscriber(modid = TridentForever.MODID)
public class trident4ever {

    public static final String MODID = "tridentforever";

    private static final double MAX_DISTANCE = 100.0D;
    private static final double SKY_HEIGHT = 400.0D;

    private static final double RETURN_ACCELERATION = 0.05D;
    private static final double SKY_RETURN_ACCELERATION = 0.08D;
    private static final double MAX_RETURN_SPEED = 2.0D;

    /*
     * Pending tridents are stored on the player.
     * copyOnDeath() preserves the attachment through player death.
     */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(
                    NeoForgeRegistries.ATTACHMENT_TYPES,
                    MODID
            );

    public static final Supplier<AttachmentType<List<ItemStack>>> PENDING =
            ATTACHMENTS.register(
                    "pending_tridents",
                    () -> AttachmentType.<List<ItemStack>>builder(
                                    () -> List.<ItemStack>of()
                            )
                            .serialize(ItemStack.CODEC.listOf())
                            .copyOnDeath()
                            .build()
            );

    /*
     * Tracks active tridents.
     *
     * In-memory only: tracking is lost when the server stops.
     */
    private record Tracked(
            UUID owner,
            int level,
            boolean skyFalling
    ) {}

    private static final Map<UUID, Tracked> TRACKED = new HashMap<>();

    /*
     * Prevents the replacement entity created by skyFall() from
     * being mistaken for an ordinary newly thrown trident.
     *
     * This set is only a short-lived runtime guard.
     */
    private static final Set<UUID> SKY_FALLING = new HashSet<>();

    public TridentForever(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        LogUtils.getLogger().info("Trident Forever loaded");
    }

    // ------------------------------------------------------------
    // TRACKING
    // ------------------------------------------------------------

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        if (!(event.getEntity() instanceof ThrownTrident trident)) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }

        /*
         * A newly created sky-fall entity is registered explicitly
         * by skyFall(), so it must not be treated as a normal throw.
         */
        if (SKY_FALLING.remove(trident.getUUID())) {
            return;
        }

        /*
         * Existing entities loaded from disk are not necessarily
         * newly thrown. Never infer new tracking data from them:
         * that could incorrectly track an old or unrelated trident.
         */
        if (event.loadedFromDisk()) {
            return;
        }

        if (!(trident.getOwner() instanceof ServerPlayer owner)) {
            return;
        }

        if (trident.pickup != AbstractArrow.Pickup.ALLOWED) {
            return;
        }

        Holder<Enchantment> loyalty = event.getLevel()
                .registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.LOYALTY);

        int level = EnchantmentHelper.getItemEnchantmentLevel(
                loyalty,
                trident.getPickupItemStackOrigin()
        );

        if (level <= 0) {
            return;
        }

        TRACKED.put(
                trident.getUUID(),
                new Tracked(owner.getUUID(), level, false)
        );
    }

    /*
     * Clean up tracking when a trident is really gone
     * (picked up, killed, discarded). Unloading from a chunk
     * does NOT count, so those entries are kept.
     */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        if (!(event.getEntity() instanceof ThrownTrident trident)) {
            return;
        }

        Entity.RemovalReason reason = trident.getRemovalReason();

        if (reason != null && reason.shouldDestroy()) {
            TRACKED.remove(trident.getUUID());
            SKY_FALLING.remove(trident.getUUID());
        }
    }

    // ------------------------------------------------------------
    // MAIN TICK
    // ------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (TRACKED.isEmpty()) {
            return;
        }

        MinecraftServer server = event.getServer();

        for (UUID id : new ArrayList<>(TRACKED.keySet())) {
            Tracked info = TRACKED.get(id);

            if (info == null) {
                continue;
            }

            ThrownTrident trident = findTrident(server, id);

            /*
             * Do not remove tracking simply because the entity
             * is not currently loaded: its chunk might be unloaded.
             * Real removals are handled by onLeave().
             */
            if (trident == null) {
                continue;
            }

            ServerPlayer owner =
                    server.getPlayerList().getPlayer(info.owner());

            if (owner == null) {
                // Owner offline: do not interfere with vanilla.
                continue;
            }

            process(id, info, trident, owner);
        }
    }

    private static void process(
            UUID id,
            Tracked info,
            ThrownTrident trident,
            ServerPlayer owner
    ) {
        int level = info.level();

        boolean sameDimension = trident.level() == owner.level();

        // --------------------------------------------------------
        // OWNER DEAD
        // --------------------------------------------------------

        if (!owner.isAlive()) {
            if (level >= 4 || (level >= 3 && sameDimension)) {
                toOwner(id, trident, owner);
            } else {
                TRACKED.remove(id);
            }

            return;
        }

        // --------------------------------------------------------
        // OTHER DIMENSION
        // --------------------------------------------------------

        if (!sameDimension) {
            if (level >= 4) {
                skyFall(id, trident, owner, level);
            } else {
                TRACKED.remove(id);
            }

            return;
        }

        // --------------------------------------------------------
        // VOID
        // --------------------------------------------------------

        if (trident.getY()
                < trident.level().getMinBuildHeight() - 16) {

            if (level >= 2) {
                toOwner(id, trident, owner);
            } else {
                TRACKED.remove(id);
            }

            return;
        }

        // --------------------------------------------------------
        // TOO FAR AWAY
        // --------------------------------------------------------

        double dx = trident.getX() - owner.getX();
        double dz = trident.getZ() - owner.getZ();

        if (dx * dx + dz * dz
                > MAX_DISTANCE * MAX_DISTANCE) {

            skyFall(id, trident, owner, level);
            return;
        }

        // --------------------------------------------------------
        // SKY-FALL RETURN
        // --------------------------------------------------------

        if (info.skyFalling()) {
            homeTowardsOwner(
                    trident,
                    owner,
                    SKY_RETURN_ACCELERATION
            );

            return;
        }

        // --------------------------------------------------------
        // LOYALTY I AND II: FASTER RETURN
        // --------------------------------------------------------

        if (level < 3 && trident.isNoPhysics()) {
            double acceleration =
                    RETURN_ACCELERATION * (3 - level);

            homeTowardsOwner(
                    trident,
                    owner,
                    acceleration
            );
        }
    }

    // ------------------------------------------------------------
    // FIND ENTITY
    // ------------------------------------------------------------

    private static ThrownTrident findTrident(
            MinecraftServer server,
            UUID id
    ) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);

            if (entity instanceof ThrownTrident trident
                    && !trident.isRemoved()) {
                return trident;
            }
        }

        return null;
    }

    // ------------------------------------------------------------
    // HOMING MOVEMENT
    // ------------------------------------------------------------

    private static void homeTowardsOwner(
            ThrownTrident trident,
            ServerPlayer owner,
            double acceleration
    ) {
        Vec3 target = owner.getEyePosition()
                .subtract(trident.position());

        if (target.lengthSqr() < 1.0E-6D) {
            return;
        }

        Vec3 direction = target.normalize();

        Vec3 velocity = trident.getDeltaMovement()
                .add(direction.scale(acceleration));

        double speed = velocity.length();

        if (speed > MAX_RETURN_SPEED) {
            velocity = velocity.scale(
                    MAX_RETURN_SPEED / speed
            );
        }

        trident.setDeltaMovement(velocity);
        trident.setNoPhysics(true);
        trident.hasImpulse = true;
    }

    // ------------------------------------------------------------
    // DIRECT RETURN
    // ------------------------------------------------------------

    private static void toOwner(
            UUID id,
            ThrownTrident trident,
            ServerPlayer owner
    ) {
        ItemStack stack =
                trident.getPickupItemStackOrigin().copy();

        trident.discard();

        TRACKED.remove(id);
        SKY_FALLING.remove(id);

        giveOrQueue(owner, stack);
    }

    // ------------------------------------------------------------
    // SKY FALL
    // ------------------------------------------------------------

    private static void skyFall(
            UUID id,
            ThrownTrident old,
            ServerPlayer owner,
            int level
    ) {
        ItemStack stack =
                old.getPickupItemStackOrigin().copy();

        old.discard();

        TRACKED.remove(id);
        SKY_FALLING.remove(id);

        ServerLevel destination = owner.serverLevel();

        /*
         * The replacement starts above the owner.
         * It receives its tracking entry explicitly so that it
         * cannot accidentally inherit the old entity's state.
         */
        ThrownTrident replacement = new ThrownTrident(
                destination,
                owner,
                stack
        );

        replacement.setPos(
                owner.getX(),
                owner.getY() + SKY_HEIGHT,
                owner.getZ()
        );

        replacement.setDeltaMovement(0.0D, -1.0D, 0.0D);
        replacement.setNoPhysics(true);
        replacement.pickup = AbstractArrow.Pickup.ALLOWED;

        UUID newId = replacement.getUUID();

        TRACKED.put(
                newId,
                new Tracked(
                        owner.getUUID(),
                        level,
                        true
                )
        );

        SKY_FALLING.add(newId);

        destination.addFreshEntity(replacement);
    }

    // ------------------------------------------------------------
    // DELIVERY
    // ------------------------------------------------------------

    private static void giveOrQueue(
            ServerPlayer player,
            ItemStack stack
    ) {
        if (stack.isEmpty()) {
            return;
        }

        if (player.isAlive()) {
            give(player, stack);
            return;
        }

        List<ItemStack> pending =
                new ArrayList<>(player.getData(PENDING));

        pending.add(stack.copy());

        player.setData(PENDING, pending);
    }

    private static void give(
            ServerPlayer player,
            ItemStack stack
    ) {
        if (stack.isEmpty()) {
            return;
        }

        /*
         * Inventory.add() can leave a remainder when the inventory
         * is full. Drop that remainder into the world.
         */
        ItemStack remainder = stack.copy();

        player.getInventory().add(remainder);

        if (!remainder.isEmpty()) {
            player.drop(remainder, false);
        }
    }

    private static void flush(ServerPlayer player) {
        if (!player.hasData(PENDING)) {
            return;
        }

        List<ItemStack> pending =
                new ArrayList<>(player.getData(PENDING));

        if (pending.isEmpty()) {
            return;
        }

        /*
         * Clear the queue before delivery so that a subsequent
         * login/respawn event cannot deliver the same items again.
         */
        player.setData(PENDING, List.<ItemStack>of());

        for (ItemStack stack : pending) {
            give(player, stack.copy());
        }
    }

    // ------------------------------------------------------------
    // PLAYER EVENTS
    // ------------------------------------------------------------

    @SubscribeEvent
    public static void onRespawn(
            PlayerEvent.PlayerRespawnEvent event
    ) {
        if (event.getEntity() instanceof ServerPlayer player) {
            flush(player);
        }
    }

    @SubscribeEvent
    public static void onLogin(
            PlayerEvent.PlayerLoggedInEvent event
    ) {
        if (event.getEntity() instanceof ServerPlayer player) {
            flush(player);
        }
    }

    // ------------------------------------------------------------
    // SERVER STOP
    // ------------------------------------------------------------

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TRACKED.clear();
        SKY_FALLING.clear();
    }
}
