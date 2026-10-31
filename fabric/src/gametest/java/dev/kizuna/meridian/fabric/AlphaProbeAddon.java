package dev.kizuna.meridian.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Collection;
import net.fabricmc.api.ModInitializer;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.Entity;

/** 测试附属甲：在 qi 下挂带可选目标与单词参数的节点，并登记一个观察频道。 */
public final class AlphaProbeAddon implements ModInitializer {
    static final String NAME = "alpha_probe";
    static volatile Collection<? extends Entity> lastTargets;
    static volatile String lastWord;
    static volatile MeridianCommands.WatchSwitch lastWatchSwitch;

    @Override
    public void onInitialize() {
        // 形状同 /meridian qi get [<target>] <meridian>：省略目标分支与目标分支共用同一段单词参数。
        MeridianCommands.register(MeridianCommands.Branch.QI, NAME, MeridianCommands.PLAYER, (node, context) ->
                MeridianCommands.optionalEntities(node, (parent, targets) -> parent.then(
                        Commands.argument("word", StringArgumentType.word()).executes(command -> {
                            lastTargets = targets.resolve(command);
                            lastWord = StringArgumentType.getString(command, "word");
                            return 1;
                        }))));

        // 频道只记录最后一次开关，验证 watch 下的补全与执行都来自登记。
        MeridianCommands.registerWatchChannel(NAME, MeridianCommands.PLAYER, (source, targets, watchSwitch) -> {
            lastTargets = targets;
            lastWatchSwitch = watchSwitch;
            return targets.size();
        });
    }
}
