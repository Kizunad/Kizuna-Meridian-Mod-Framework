package dev.kizuna.meridian.fabric;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.error.MeridianErrors;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.world.entity.Entity;

/**
 * /meridian 的唯一注册点。附属在 onInitialize 里登记自己的三级节点或观察频道，服务器每次建指令树时由这里
 * 创建一次根节点并合并全部登记；没装的附属不会登记，它的节点自然不存在。
 *
 * <p>权限在登记时声明，由注册点套在三级节点上，子树不再自设 requires。可选目标统一用
 * {@link #optionalEntity}、{@link #optionalEntities}、{@link #optionalPlayers} 生成：先注册省略目标、作用于执行者的分支，
 * 再注册要求权限 {@link #GAMEMASTER} 的目标分支。两条分支都能完整解析同一输入时（例如目标位置上的玩家名
 * 也是合法的经脉名），Brigadier 取先注册的那条，所以 {@code x <word>} 总是按省略目标解析。
 */
public final class MeridianCommands {
    /** 只作用于执行者本人的玩家指令。 */
    public static final int PLAYER = 0;
    /** 指定他人目标或调试性质的指令，与原版管理指令同级。 */
    public static final int GAMEMASTER = 2;

    private static final String ROOT = "meridian";
    /** 下标就是原版权限等级 0 到 4。 */
    private static final List<PermissionCheck> LEVEL_CHECKS = List.of(Commands.LEVEL_ALL, Commands.LEVEL_MODERATORS,
            Commands.LEVEL_GAMEMASTERS, Commands.LEVEL_ADMINS, Commands.LEVEL_OWNERS);
    private static final Pattern NODE_NAME = Pattern.compile("[a-z0-9_]+");
    private static final Map<Branch, Map<String, Registration>> NODES = new EnumMap<>(Branch.class);
    private static final Map<Branch, BranchCommand> BRANCH_COMMANDS = new EnumMap<>(Branch.class);
    private static final Map<String, WatchRegistration> WATCH_CHANNELS = new LinkedHashMap<>();

    private MeridianCommands() {
        // 登记表是进程级静态状态，与服务器实例无关，/reload 重建指令树时读取同一份登记。
    }

    /** /meridian 下的二级节点；按领域固定，附属不能另开新的二级节点。 */
    public enum Branch {
        BODY("body"),
        QI("qi"),
        OPENING("opening"),
        TECHNIQUE("technique"),
        HUMANOID("humanoid"),
        COMBAT("combat"),
        PROGRESSION("progression"),
        WATCH("watch"),
        DEBUG("debug");

        private final String literal;

        Branch(String literal) {
            // 枚举顺序就是建树顺序，补全列表按这个顺序出现。
            this.literal = literal;
        }

        public String literal() {
            // 文档、报错和测试按玩家输入的字面量引用节点。
            return literal;
        }
    }

    /** 附属挂在三级节点下的子树；node 已是该三级字面量，附属只往下添加参数和执行入口。 */
    @FunctionalInterface
    public interface Subtree {
        void build(LiteralArgumentBuilder<CommandSourceStack> node, CommandBuildContext context);
    }

    /** /meridian watch &lt;频道&gt; 之后的开关方向；字面量就是玩家输入的 on 或 off。 */
    public enum WatchSwitch {
        ON("on"),
        OFF("off");

        private final String literal;

        WatchSwitch(String literal) {
            // 枚举顺序就是频道下字面量的建树和补全顺序。
            this.literal = literal;
        }

        public String literal() {
            // 建树时作为频道下的字面量，文档和测试按同一输入引用。
            return literal;
        }
    }

    /** /meridian watch &lt;频道&gt; on|off [&lt;targets&gt;] 的执行入口；省略目标时 targets 只有执行者本人。 */
    @FunctionalInterface
    public interface WatchChannel {
        int watch(CommandSourceStack source, Collection<? extends Entity> targets, WatchSwitch watchSwitch)
                throws CommandSyntaxException;
    }

    /** 在执行时取出本分支的目标：省略目标分支返回执行者，目标分支解析选择器。 */
    @FunctionalInterface
    public interface Targets<T> {
        T resolve(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }

    /** 可选目标之后的同一段子树；会对省略目标分支和目标分支各调用一次。 */
    @FunctionalInterface
    public interface TargetedTree<T> {
        void attach(ArgumentBuilder<CommandSourceStack, ?> parent, Targets<T> targets);
    }

    public static synchronized void register(Branch branch, String name, int permission, Subtree subtree) {
        // 校验登记参数：watch 下的字面量由频道生成，不接受直接挂子树。
        Objects.requireNonNull(branch, MeridianErrors.Commands.SUBTREE_REQUIRED);
        Objects.requireNonNull(subtree, MeridianErrors.Commands.SUBTREE_REQUIRED);
        if (branch == Branch.WATCH) {
            throw MeridianErrors.invalid(MeridianErrors.Commands.WATCH_BRANCH);
        }
        requireNodeName(name);
        requirePermission(permission);

        // 同名三级节点无法判断归属，在附属初始化时立即失败，而不是让 Brigadier 静默合并两棵子树。
        Map<String, Registration> nodes = NODES.computeIfAbsent(branch, ignored -> new LinkedHashMap<>());
        if (nodes.containsKey(name)) {
            throw MeridianErrors.inconsistent(MeridianErrors.Commands.duplicateNode(branch.literal(), name));
        }
        nodes.put(name, new Registration(permission, subtree));
    }

    public static synchronized void registerBranchCommand(Branch branch, int permission,
                                                          Command<CommandSourceStack> command) {
        // 二级节点自身可执行（如 /meridian opening），同样只能有一个归属。
        Objects.requireNonNull(branch, MeridianErrors.Commands.SUBTREE_REQUIRED);
        Objects.requireNonNull(command, MeridianErrors.Commands.SUBTREE_REQUIRED);
        if (branch == Branch.WATCH) {
            throw MeridianErrors.invalid(MeridianErrors.Commands.WATCH_BRANCH);
        }
        requirePermission(permission);
        if (BRANCH_COMMANDS.containsKey(branch)) {
            throw MeridianErrors.inconsistent(MeridianErrors.Commands.duplicateBranchCommand(branch.literal()));
        }
        BRANCH_COMMANDS.put(branch, new BranchCommand(permission, command));
    }

    public static synchronized void registerWatchChannel(String channel, int permission, WatchChannel watch) {
        // 频道名直接成为 watch 下的字面量，因此 Tab 补全就是已登记频道的列表。
        Objects.requireNonNull(watch, MeridianErrors.Commands.SUBTREE_REQUIRED);
        requireNodeName(channel);
        requirePermission(permission);
        if (WATCH_CHANNELS.containsKey(channel)) {
            throw MeridianErrors.inconsistent(MeridianErrors.Commands.duplicateWatchChannel(channel));
        }
        WATCH_CHANNELS.put(channel, new WatchRegistration(permission, watch));
    }

    public static void optionalEntity(ArgumentBuilder<CommandSourceStack, ?> action, TargetedTree<Entity> tree) {
        // 单个目标：省略时是执行者实体，指定时用原版单实体选择器。
        tree.attach(action, context -> context.getSource().getEntityOrException());
        RequiredArgumentBuilder<CommandSourceStack, EntitySelector> target =
                targetArgument("target", EntityArgument.entity());
        tree.attach(target, context -> EntityArgument.getEntity(context, "target"));
        action.then(target);
    }

    public static void optionalEntities(ArgumentBuilder<CommandSourceStack, ?> action,
                                        TargetedTree<Collection<? extends Entity>> tree) {
        // 多个目标：省略时只有执行者实体，指定时用原版多实体选择器。
        tree.attach(action, context -> List.of(context.getSource().getEntityOrException()));
        RequiredArgumentBuilder<CommandSourceStack, EntitySelector> targets =
                targetArgument("targets", EntityArgument.entities());
        tree.attach(targets, context -> EntityArgument.getEntities(context, "targets"));
        action.then(targets);
    }

    public static void optionalPlayers(ArgumentBuilder<CommandSourceStack, ?> action,
                                       TargetedTree<Collection<ServerPlayer>> tree) {
        // 只对玩家有意义的指令：省略时执行者必须是玩家，指定时用原版玩家选择器。
        tree.attach(action, context -> List.of(context.getSource().getPlayerOrException()));
        RequiredArgumentBuilder<CommandSourceStack, EntitySelector> targets =
                targetArgument("targets", EntityArgument.players());
        tree.attach(targets, context -> EntityArgument.getPlayers(context, "targets"));
        action.then(targets);
    }

    static synchronized void build(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        // 按固定顺序生成二级节点；没有任何登记的二级节点不出现。
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);
        int rootPermission = Integer.MAX_VALUE;
        for (Branch branch : Branch.values()) {
            BuiltBranch built = branch == Branch.WATCH ? buildWatch() : buildBranch(branch, context);
            if (built == null) {
                continue;
            }
            root.then(built.node());
            rootPermission = Math.min(rootPermission, built.permission());
        }

        // 只要有任一附属登记就注册根节点；根与二级节点取子节点里的最低权限，玩家看不到全是管理指令的分支。
        if (root.getArguments().isEmpty()) {
            return;
        }
        dispatcher.register(root.requires(requirement(rootPermission)));
    }

    private static BuiltBranch buildBranch(Branch branch, CommandBuildContext context) {
        // 读取本二级节点的全部登记；三级节点各自套上登记时声明的权限，覆盖子树里可能自设的 requires。
        Map<String, Registration> nodes = NODES.getOrDefault(branch, Map.of());
        BranchCommand branchCommand = BRANCH_COMMANDS.get(branch);
        if (nodes.isEmpty() && branchCommand == null) {
            return null;
        }
        LiteralArgumentBuilder<CommandSourceStack> branchNode = Commands.literal(branch.literal());
        int branchPermission = Integer.MAX_VALUE;
        for (Map.Entry<String, Registration> entry : nodes.entrySet()) {
            Registration registration = entry.getValue();
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(entry.getKey());
            registration.subtree().build(node, context);
            node.requires(requirement(registration.permission()));
            branchNode.then(node);
            branchPermission = Math.min(branchPermission, registration.permission());
        }

        // 二级节点自身的执行入口：节点可见性取最低权限，执行时再按入口自己的权限检查。
        if (branchCommand != null) {
            int commandPermission = branchCommand.permission();
            branchNode.executes(commandContext -> {
                if (!requirement(commandPermission).test(commandContext.getSource())) {
                    throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
                }
                return branchCommand.command().run(commandContext);
            });
            branchPermission = Math.min(branchPermission, commandPermission);
        }
        branchNode.requires(requirement(branchPermission));
        return new BuiltBranch(branchNode, branchPermission);
    }

    private static BuiltBranch buildWatch() {
        // 每个频道生成 on|off [<targets>]；省略目标时只开关执行者本人，带他人目标要权限 2。
        if (WATCH_CHANNELS.isEmpty()) {
            return null;
        }
        LiteralArgumentBuilder<CommandSourceStack> watchNode = Commands.literal(Branch.WATCH.literal());
        int watchPermission = Integer.MAX_VALUE;
        for (Map.Entry<String, WatchRegistration> entry : WATCH_CHANNELS.entrySet()) {
            WatchRegistration registration = entry.getValue();
            LiteralArgumentBuilder<CommandSourceStack> channelNode = Commands.literal(entry.getKey());
            for (WatchSwitch watchSwitch : WatchSwitch.values()) {
                channelNode.then(watchToggle(watchSwitch, registration.channel()));
            }
            channelNode.requires(requirement(registration.permission()));
            watchNode.then(channelNode);
            watchPermission = Math.min(watchPermission, registration.permission());
        }
        watchNode.requires(requirement(watchPermission));
        return new BuiltBranch(watchNode, watchPermission);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> watchToggle(WatchSwitch watchSwitch,
                                                                         WatchChannel channel) {
        // on 与 off 共用同一个可选目标形状，频道实现只看开关方向和最终目标集合。
        LiteralArgumentBuilder<CommandSourceStack> toggle = Commands.literal(watchSwitch.literal());
        optionalEntities(toggle, (parent, targets) -> parent.executes(context ->
                channel.watch(context.getSource(), targets.resolve(context), watchSwitch)));
        return toggle;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> targetArgument(
            String name, EntityArgument type) {
        // 指定他人目标一律要求权限 2，与原版选择器本身的权限要求一致。
        return Commands.argument(name, type).requires(requirement(GAMEMASTER));
    }

    private static Predicate<CommandSourceStack> requirement(int permission) {
        // 26.2 按来源的权限集判断：整数等级换成原版同级的检查；权限 0 是总能通过的检查，对任何来源都成立。
        return Commands.hasPermission(LEVEL_CHECKS.get(permission));
    }

    private static void requireNodeName(String name) {
        // 节点名直接作为字面量输入，只接受不需要引号的小写单词。
        if (name == null || !NODE_NAME.matcher(name).matches()) {
            throw MeridianErrors.invalid(MeridianErrors.Commands.NODE_NAME);
        }
    }

    private static void requirePermission(int permission) {
        // 原版权限等级只有 0 到 4。
        if (permission < PLAYER || permission >= LEVEL_CHECKS.size()) {
            throw MeridianErrors.invalid(MeridianErrors.Commands.PERMISSION);
        }
    }

    private record Registration(int permission, Subtree subtree) {
    }

    private record BranchCommand(int permission, Command<CommandSourceStack> command) {
    }

    private record WatchRegistration(int permission, WatchChannel channel) {
    }

    private record BuiltBranch(LiteralArgumentBuilder<CommandSourceStack> node, int permission) {
    }
}
