package dev.kizuna.meridian.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.CommandNode;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;

/** /meridian 注册点回归：两个测试附属的子树合并补全、未登记附属无节点、同名三级节点报错。 */
public final class MeridianCommandsGameTest {
    private static final int COMMAND_SUCCEEDED = 1;
    private static final int COMMAND_FAILED = 0;

    @GameTest
    public void registeredSubtreesCompleteUnderOneRoot(GameTestHelper context) {
        // 两个附属挂在同一个 qi 下，补全同时列出；debug 只对管理员可见，watch 频道同样来自登记。
        MinecraftServer server = context.getLevel().getServer();
        CommandSourceStack operator = server.createCommandSourceStack();
        CommandSourceStack player = operator.withPermission(LevelBasedPermissionSet.ALL);
        context.assertTrue(completions(server, operator, "meridian ").containsAll(List.of("qi", "watch", "debug")),
                "/meridian 缺少已登记的二级节点");
        // 分发器补全不过滤权限，玩家能看到什么取决于下发的指令树，也就是节点的 requires。
        CommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot().getChild("meridian");
        context.assertTrue(root.getChild("qi").canUse(player) && !root.getChild("debug").canUse(player),
                "权限 0 应看到 qi，不应看到只有管理节点的 debug");
        context.assertTrue(completions(server, operator, "meridian qi ")
                        .containsAll(List.of(AlphaProbeAddon.NAME, BetaProbeAddon.NAME)),
                "同一二级节点下两个附属的子树没有同时补全");
        List<String> switches = completions(server, operator, "meridian watch alpha_probe ");
        context.assertTrue(completions(server, operator, "meridian watch ").contains(AlphaProbeAddon.NAME)
                        && switches.containsAll(List.of("on", "off")),
                "watch 频道或开关没有补全");

        // 可选目标：x <word> 走省略目标分支；单词位置写的是合法 UUID 时也按单词解析；x <target> <word> 才指定他人。
        Entity self = context.spawn(EntityTypes.PIG, 1, 2, 1);
        Entity other = context.spawn(EntityTypes.PIG, 2, 2, 2);
        CommandSourceStack asSelf = operator.withEntity(self);
        String otherId = other.getStringUUID();
        context.assertTrue(run(server, asSelf, "meridian qi alpha_probe ren") == COMMAND_SUCCEEDED
                        && targeted(self) && "ren".equals(AlphaProbeAddon.lastWord),
                "省略目标时没有作用于执行者");
        context.assertTrue(run(server, asSelf, "meridian qi alpha_probe " + otherId) == COMMAND_SUCCEEDED
                        && targeted(self) && otherId.equals(AlphaProbeAddon.lastWord),
                "歧义输入没有按先注册的省略目标分支解析");
        context.assertTrue(run(server, asSelf, "meridian qi alpha_probe " + otherId + " du") == COMMAND_SUCCEEDED
                        && targeted(other) && "du".equals(AlphaProbeAddon.lastWord),
                "指定目标时没有作用于目标");
        context.assertTrue(run(server, asSelf.withPermission(LevelBasedPermissionSet.ALL),
                        "meridian qi alpha_probe " + otherId + " du") == COMMAND_FAILED,
                "权限 0 不应能指定他人目标");

        // 观察频道的开关与可选目标同样生效。
        context.assertTrue(run(server, asSelf, "meridian watch alpha_probe on") == COMMAND_SUCCEEDED
                        && AlphaProbeAddon.lastWatchSwitch == MeridianCommands.WatchSwitch.ON && targeted(self),
                "watch on 没有作用于执行者");
        context.assertTrue(run(server, asSelf, "meridian watch alpha_probe off " + otherId) == COMMAND_SUCCEEDED
                        && AlphaProbeAddon.lastWatchSwitch == MeridianCommands.WatchSwitch.OFF && targeted(other),
                "watch off 没有作用于指定目标");
        context.succeed();
    }

    @GameTest
    public void unregisteredAddonHasNoNode(GameTestHelper context) {
        // 丙没有列入入口：它要挂的 combat 二级节点和三级节点都不存在，指令无法执行。
        MinecraftServer server = context.getLevel().getServer();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("meridian");
        context.assertTrue(root != null, "/meridian 根节点不存在");
        context.assertTrue(root.getChild(MeridianCommands.Branch.COMBAT.literal()) == null,
                "未登记附属的 combat 节点不应存在");
        context.assertTrue(root.getChild(MeridianCommands.Branch.QI.literal()).getChild(GammaProbeAddon.NAME) == null,
                "未登记附属的三级节点不应存在");
        context.assertTrue(run(server, server.createCommandSourceStack(), "meridian combat " + GammaProbeAddon.NAME)
                        == COMMAND_FAILED,
                "未登记附属的指令不应能执行");
        context.succeed();
    }

    @GameTest
    public void duplicateThirdLevelNodeFails(GameTestHelper context) {
        // 同名三级节点、同名频道与直接挂到 watch 下都在登记时报错，不进入指令树。
        IllegalStateException duplicateNode = expect(IllegalStateException.class, () ->
                MeridianCommands.register(MeridianCommands.Branch.QI, AlphaProbeAddon.NAME, MeridianCommands.PLAYER,
                        (node, buildContext) -> node.executes(command -> 1)));
        context.assertTrue(duplicateNode != null && duplicateNode.getMessage().contains("/meridian qi alpha_probe"),
                "同名三级节点没有报错");
        context.assertTrue(expect(IllegalStateException.class, () ->
                        MeridianCommands.registerWatchChannel(AlphaProbeAddon.NAME, MeridianCommands.PLAYER,
                                (source, targets, watchSwitch) -> 1)) != null,
                "同名观察频道没有报错");
        context.assertTrue(expect(IllegalArgumentException.class, () ->
                        MeridianCommands.register(MeridianCommands.Branch.WATCH, "direct", MeridianCommands.PLAYER,
                                (node, buildContext) -> node.executes(command -> 1))) != null,
                "watch 下直接挂子树没有报错");

        // 失败的登记不留痕迹：qi 下仍只有原来的子树。
        CommandNode<CommandSourceStack> qi = context.getLevel().getServer().getCommands().getDispatcher().getRoot()
                .getChild("meridian").getChild(MeridianCommands.Branch.QI.literal());
        context.assertTrue(qi.getChildren().size() == 2, "失败的登记改变了指令树");
        context.succeed();
    }

    private static boolean targeted(Entity entity) {
        // 选择器返回的集合类型不固定，按元素顺序比较最后一次执行的目标。
        return AlphaProbeAddon.lastTargets != null && List.copyOf(AlphaProbeAddon.lastTargets).equals(List.of(entity));
    }

    private static List<String> completions(MinecraftServer server, CommandSourceStack source, String input) {
        // 按玩家在聊天栏里按 Tab 的同一路径取补全文本。
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join().getList().stream()
                .map(Suggestion::getText)
                .toList();
    }

    private static int run(MinecraftServer server, CommandSourceStack source, String command) {
        // 与聊天栏执行相同；26.2 的执行入口不再返回结果，改由来源的结果回调取得，解析失败或执行失败都返回 0。
        int[] result = {COMMAND_FAILED};
        server.getCommands().performPrefixedCommand(
                source.withCallback((success, value) -> result[0] = success ? value : COMMAND_FAILED), command);
        return result[0];
    }

    private static <T extends RuntimeException> T expect(Class<T> type, Runnable registration) {
        // 只接受预期类型的异常；没有抛出时返回 null 交给断言报告。
        try {
            registration.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception)) {
                return type.cast(exception);
            }
            throw exception;
        }
        return null;
    }
}
