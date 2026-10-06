package io.github.mojolowjo.entropycompanion;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The mod entry: client only (no side effect on a server, no network channels, so a server without it takes the
 * client as it is). The client tick only compares a clock; when a post is due it copies the player's state and
 * hands it to one background thread, which does the (blocking, 2 s at most) HTTP post. 0.2.0 adds /bot, the keys,
 * the quick menu, the reply overlay and the box view (see {@link Companion}).
 */
@Mod(value = EntropyCompanion.MODID, dist = Dist.CLIENT)
public final class EntropyCompanion {
    public static final String MODID = "entropycompanion";
    private static final Logger LOG = LogUtils.getLogger();

    private final Path configFile = FMLPaths.CONFIGDIR.get().resolve(CompanionConfig.FILE_NAME);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entropy-companion");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final PostLoop loop;
    private final Companion companion;

    private volatile CompanionConfig config;
    private volatile long configMtime = Long.MIN_VALUE;
    private volatile long lastConfigCheck;

    public EntropyCompanion(IEventBus modBus) {
        config = CompanionConfig.load(configFile); // writes the defaults on the first run
        configMtime = mtime();
        HttpSender http = new HttpSender();
        loop = new PostLoop(this::currentConfig, http, m -> LOG.info("[entropycompanion] {}", m), System::currentTimeMillis);
        companion = new Companion(this::currentConfig, loop, http);
        Companion.INSTANCE = companion;
        modBus.addListener(RegisterKeyMappingsEvent.class, Keys::register);
        modBus.addListener(RegisterGuiLayersEvent.class, e ->
                e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "replies"), Overlay::render));
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> {
            try {
                BotCommand.register(e, companion, currentConfig().commandAlias);
            } catch (RuntimeException ex) {
                companion.error("commands", ex);
            }
        });
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, BoxRender::onRender);
        LOG.info("[entropycompanion] {}", config);
        String alias = config.commandAlias.isEmpty() ? "" : ",/" + config.commandAlias;
        LOG.info("[entropycompanion] {} ready: commands /bot{}, keys point=V menu=G (rebind in Controls), overlay {}, box view off",
                Companion.VERSION, alias, config.overlaySeconds > 0 ? "on" : "off");
    }

    private long mtime() {
        try {
            return Files.getLastModifiedTime(configFile).toMillis();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /** The config, read again when the file has changed (any thread; at most one look a second). */
    private CompanionConfig currentConfig() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheck >= 1000) {
            synchronized (this) {
                if (now - lastConfigCheck >= 1000) {
                    lastConfigCheck = now;
                    long m = mtime();
                    if (m != configMtime) {
                        configMtime = m;
                        config = CompanionConfig.load(configFile);
                        LOG.info("[entropycompanion] config reloaded: {}", config);
                    }
                }
            }
        }
        return config;
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Keys.tick(mc, companion);
            companion.tickBoxes();
        } catch (RuntimeException e) {
            companion.error("keys", e);
        }
        try {
            if (mc.player == null || mc.level == null) return;
            if (!loop.due() || !busy.compareAndSet(false, true)) return;
            OwnerState.State st = state(mc, mc.player);
            worker.execute(() -> {
                try {
                    loop.attemptBody(at -> companion.ownerState.json(st, at));
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            busy.set(false); // never let an exception out of the client tick
            companion.error("tick", e);
        }
    }

    /** The owner's state for the v2 post, copied on the client thread. */
    private static OwnerState.State state(Minecraft mc, LocalPlayer p) {
        Inventory inv = p.getInventory();
        List<OwnerState.Stack> stacks = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            stacks.add(new OwnerState.Stack(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(),
                    s.get(DataComponents.FOOD) != null, s.getItem() instanceof BlockItem));
        }
        ItemStack held = p.getMainHandItem();
        return new OwnerState.State(mc.getUser().getName(), p.getX(), p.getY(), p.getZ(), mc.level.dimension().location().toString(),
                p.getHealth(), p.getMaxHealth(), p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(), p.getArmorValue(),
                p.getYRot(), p.getXRot(), held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), stacks);
    }
}
