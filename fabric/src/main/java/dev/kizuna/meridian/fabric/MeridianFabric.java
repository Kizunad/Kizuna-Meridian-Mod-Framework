package dev.kizuna.meridian.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/** Meridian 公共 Fabric 入口：在服务器建指令树时生成唯一的 /meridian 根。 */
public final class MeridianFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // 附属在各自 onInitialize 里登记，建树推迟到服务器创建指令表时，所以模组初始化顺序不影响结果；
        // /reload 重建指令表时再次读取同一份登记。
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->
                MeridianCommands.build(dispatcher, context));
    }
}
