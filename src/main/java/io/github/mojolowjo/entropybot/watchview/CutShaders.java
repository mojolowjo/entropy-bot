package io.github.mojolowjo.entropybot.watchview;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.util.function.Consumer;

/**
 * The cutaway's two core shaders ({@code assets/entropybot/shaders/core/watch_cut_tex} and {@code watch_cut_flat}, 0.16.1):
 * vanilla's position_tex_color / position_color plus three uniforms (CutA, CutB, CutRadius) that drop the fragments
 * between the camera and the bot ({@link Cutaway}). (Since 0.19.1 the uniforms are CutCam, CutMin, CutMax, CutMargin,
 * CutCone (> 0 = cone mode, its radius) and CutShadow (>= 0 = shadow mode, its margin; wins over CutCone).) Used only for the tunnel view's own two vertex buffers, so Sodium's
 * terrain shaders are never touched. Registered through NeoForge's {@code RegisterShadersEvent} (mod bus; posted from
 * {@code GameRenderer.reloadShaders} at start and on every resource reload). Every failure is caught here: a shader
 * that fails to load, compile or link is left null with the reason, the view then draws without the cut and says so
 * ({@code watch tunnel status}, {@code check}: tunnelcut). A program that links badly is caught by reading
 * GL_LINK_STATUS, because vanilla's {@code ProgramManager.linkShader} only logs that.
 */
public final class CutShaders {
    public static final String TEX = "watch_cut_tex", FLAT = "watch_cut_flat";
    private static volatile ShaderInstance tex, flat;
    private static volatile String error;
    private static volatile boolean eventSeen;
    private static volatile long loads;

    private CutShaders() {}

    /** The mod-bus listener (from EntropyBot). Never throws. */
    public static void onRegister(RegisterShadersEvent e) {
        eventSeen = true;
        error = null;
        register(e, TEX, DefaultVertexFormat.POSITION_TEX_COLOR, s -> tex = s);
        register(e, FLAT, DefaultVertexFormat.POSITION_COLOR, s -> flat = s);
    }

    private static void register(RegisterShadersEvent e, String name, VertexFormat format, Consumer<ShaderInstance> set) {
        try {
            ShaderInstance s = new ShaderInstance(e.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("entropybot", name), format);
            e.registerShader(s, loaded -> {
                try {
                    int ok = GlStateManager.glGetProgrami(loaded.getId(), org.lwjgl.opengl.GL20.GL_LINK_STATUS);
                    if (ok == 0) {
                        set.accept(null);
                        fail(name + ": the program did not link (GL_LINK_STATUS 0; the game log has vanilla's linking error)", null);
                        return;
                    }
                    set.accept(loaded);
                    loads++;
                } catch (Throwable t) {
                    set.accept(null);
                    fail(name + ": " + t, t);
                }
            });
        } catch (Throwable t) {
            set.accept(null);
            fail(name + ": " + t, t);
        }
    }

    private static void fail(String what, Throwable t) {
        error = what;
        if (t != null) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel cutaway shader {}: ", what, t);
        else com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel cutaway shader {}", what);
    }

    /** Called when a draw with our shader threw: stop using it until the next resource reload. */
    static void broken(String what) {
        tex = null;
        flat = null;
        fail("broke while drawing: " + what, null);
    }

    static ShaderInstance tex() { return tex; }

    static ShaderInstance flat() { return flat; }

    /** Both shaders are loaded and linked. */
    public static boolean ready() { return tex != null && flat != null; }

    /** The words for status: why the cut is not available, or null when it is. */
    public static String problem() {
        if (ready()) return null;
        if (!eventSeen) return "the shaders were never registered (RegisterShadersEvent not seen: the listener is not on the mod bus?)";
        return error != null ? error : "a shader is missing";
    }

    public static long loads() { return loads; }
}
