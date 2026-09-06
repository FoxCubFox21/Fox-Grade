package foxgrade.shim;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Fabric's 1.21.x WorldRenderEvents (removed), re-created over 26.2's LevelRenderEvents. Every
 * drawing phase is fired while the frame collects submits, so what a mod draws lands in this
 * frame's main pass; setup and end phases fire where 26.2 has them.
 */
public final class WorldRenderEventsShim {
  private WorldRenderEventsShim() { }
  public interface Start { void onStart(WorldRenderContextShim context); }
  public interface AfterSetup { void afterSetup(WorldRenderContextShim context); }
  public interface BeforeEntities { void beforeEntities(WorldRenderContextShim context); }
  public interface AfterEntities { void afterEntities(WorldRenderContextShim context); }
  public interface BeforeBlockOutline { boolean beforeBlockOutline(WorldRenderContextShim context, HitResult hitResult); }
  public interface BlockOutline { boolean onBlockOutline(WorldRenderContextShim context, WorldRenderContextShim.BlockOutlineContextShim blockOutlineContext); }
  public interface DebugRender { void beforeDebugRender(WorldRenderContextShim context); }
  public interface AfterTranslucent { void afterTranslucent(WorldRenderContextShim context); }
  public interface Last { void onLast(WorldRenderContextShim context); }
  public interface End { void onEnd(WorldRenderContextShim context); }

  public static final Event<Start> START = EventFactory.createArrayBacked(Start.class, ls -> c -> { for (Start l : ls) l.onStart(c); });
  public static final Event<AfterSetup> AFTER_SETUP = EventFactory.createArrayBacked(AfterSetup.class, ls -> c -> { for (AfterSetup l : ls) l.afterSetup(c); });
  public static final Event<BeforeEntities> BEFORE_ENTITIES = EventFactory.createArrayBacked(BeforeEntities.class, ls -> c -> { for (BeforeEntities l : ls) l.beforeEntities(c); });
  public static final Event<AfterEntities> AFTER_ENTITIES = EventFactory.createArrayBacked(AfterEntities.class, ls -> c -> { for (AfterEntities l : ls) l.afterEntities(c); });
  public static final Event<BeforeBlockOutline> BEFORE_BLOCK_OUTLINE = EventFactory.createArrayBacked(BeforeBlockOutline.class, ls -> (c, h) -> { boolean r = true; for (BeforeBlockOutline l : ls) r &= l.beforeBlockOutline(c, h); return r; });
  public static final Event<BlockOutline> BLOCK_OUTLINE = EventFactory.createArrayBacked(BlockOutline.class, ls -> (c, o) -> { boolean r = true; for (BlockOutline l : ls) r &= l.onBlockOutline(c, o); return r; });
  public static final Event<DebugRender> BEFORE_DEBUG_RENDER = EventFactory.createArrayBacked(DebugRender.class, ls -> c -> { for (DebugRender l : ls) l.beforeDebugRender(c); });
  public static final Event<AfterTranslucent> AFTER_TRANSLUCENT = EventFactory.createArrayBacked(AfterTranslucent.class, ls -> c -> { for (AfterTranslucent l : ls) l.afterTranslucent(c); });
  public static final Event<Last> LAST = EventFactory.createArrayBacked(Last.class, ls -> c -> { for (Last l : ls) l.onLast(c); });
  public static final Event<End> END = EventFactory.createArrayBacked(End.class, ls -> c -> { for (End l : ls) l.onEnd(c); });

  static {
    LevelExtractionEvents.END_EXTRACTION.register(ctx -> { WorldRenderContextImpl.camera = ctx.camera(); WorldRenderContextImpl.deltaTracker = ctx.deltaTracker(); WorldRenderContextImpl.level = ctx.level(); });
    LevelRenderEvents.START_MAIN.register(ctx -> { WorldRenderContextImpl c = new WorldRenderContextImpl(null); START.invoker().onStart(c); AFTER_SETUP.invoker().afterSetup(c); c.flush(); });
    LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
      WorldRenderContextImpl c = new WorldRenderContextImpl(ctx);
      BEFORE_ENTITIES.invoker().beforeEntities(c); c.flush();
      AFTER_ENTITIES.invoker().afterEntities(c); c.flush();
      AFTER_TRANSLUCENT.invoker().afterTranslucent(c); c.flush();
      LAST.invoker().onLast(c); c.flush();
    });
    LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, outline) -> {
      WorldRenderContextImpl c = new WorldRenderContextImpl(ctx);
      HitResult hit = Minecraft.getInstance().hitResult;
      boolean keep = BEFORE_BLOCK_OUTLINE.invoker().beforeBlockOutline(c, hit);
      if (keep && hit instanceof BlockHitResult bhr) keep = BLOCK_OUTLINE.invoker().onBlockOutline(c, c.outline(bhr));
      c.flush();
      return keep;
    });
    LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> { WorldRenderContextImpl c = new WorldRenderContextImpl(ctx); BEFORE_DEBUG_RENDER.invoker().beforeDebugRender(c); c.flush(); });
    LevelRenderEvents.END_MAIN.register(ctx -> { WorldRenderContextImpl c = new WorldRenderContextImpl(ctx); END.invoker().onEnd(c); c.flush(); });
  }
}
