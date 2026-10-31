package dev.kizuna.meridian.fabric;

import net.fabricmc.api.ModInitializer;

/** 测试附属乙：和甲挂在同一个二级节点 qi 下，另在 debug 下挂一个管理节点。 */
public final class BetaProbeAddon implements ModInitializer {
    static final String NAME = "beta_probe";

    @Override
    public void onInitialize() {
        // 同一二级节点可由多个附属各挂三级节点；debug 节点只对权限 2 可见。
        MeridianCommands.register(MeridianCommands.Branch.QI, NAME, MeridianCommands.PLAYER,
                (node, context) -> node.executes(command -> 1));
        MeridianCommands.register(MeridianCommands.Branch.DEBUG, NAME, MeridianCommands.GAMEMASTER,
                (node, context) -> node.executes(command -> 1));
    }
}
