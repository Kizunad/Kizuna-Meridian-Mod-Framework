package dev.kizuna.meridian.fabric;

import net.fabricmc.api.ModInitializer;

/** 测试附属丙：类在测试模组里，但不列入入口，模拟没有安装的附属。 */
public final class GammaProbeAddon implements ModInitializer {
    static final String NAME = "gamma_probe";

    @Override
    public void onInitialize() {
        // 只有被加载时才会登记；回归断言它的节点不存在。
        MeridianCommands.register(MeridianCommands.Branch.COMBAT, NAME, MeridianCommands.PLAYER,
                (node, context) -> node.executes(command -> 1));
    }
}
