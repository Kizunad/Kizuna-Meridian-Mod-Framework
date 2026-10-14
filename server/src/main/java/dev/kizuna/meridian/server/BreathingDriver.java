package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringChoice;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.*;
import static dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.*;

/** 单身体的主动吐纳控制。场景只持有空气和粒子位置，真元与疲劳始终在身体账本。 */
final class BreathingDriver<C> {
    private static final long STEP_MILLIS = 250;
    private static final long INPUT_LEASE_MILLIS = 750;
    private final GatheringSession<C> session;
    private final BreathingProfile profile;
    private final List<BreathParticle> particles = new ArrayList<>();
    private final List<ParticleMotion> motions = new ArrayList<>();
    private final Random random = new Random();
    private BreathControl control = BreathControl.newBuilder().setAction(HOLD).setAimX(.5).setAimY(.4).build();
    private boolean active;
    private long updatedMillis;
    private long leaseMillis;
    private long boundaryMillis;
    private double airLiters;
    private double absorbedQi;
    private String status = "准备吐纳";
    private FailureCode failure = FAILURE_CODE_UNSPECIFIED;
    private int lastOrgan;
    private double sceneSeconds;
    private double flowX;
    private double flowY;
    private double flowZ = 1;
    private double suction;
    private double blowing;
    private String bodyActionInstance;

    private static final class ParticleMotion {
        double vx, vy, vz;
        final double speed;
        final double phase;

        ParticleMotion(double speed, double phase) {
            // 各粒子保留独立速度和相位，停气后仍沿惯性游离，不会齐步静止。
            this.speed = speed;
            this.phase = phase;
        }
    }

    BreathingDriver(GatheringSession<C> session, BreathingProfile profile, BodySnapshot body,
                    OrganRecipeCatalog recipes, ActionCatalog actions) {
        // 内容只绑定真实模块，缺失或重复引用在装配时暴露，避免运行中猜测肺或膈肌身份。
        this.session = session;
        this.profile = profile;
        var organs = new HashSet<String>();
        boolean valid = profile.getOrgansCount() > 0 && profile.getOrgansCount() <= 32
                && positive(profile.getRespiratoryWatts()) && positive(profile.getAttentionWatts())
                && !profile.getRespiratoryTissueId().equals(profile.getAttentionTissueId())
                && profile.getRecipeIdsCount() > 0;
        for (BreathingOrgan organ : profile.getOrgansList()) {
            valid &= organs.add(organ.getOrganId()) && positive(organ.getAirCapacityLiters())
                    && positive(organ.getAirflowLitersPerSecond())
                    && body.structure().organs().stream().anyMatch(value -> value.id().value().equals(organ.getOrganId()));
        }
        for (String id : List.of(profile.getRespiratoryTissueId(), profile.getAttentionTissueId())) {
            valid &= body.structure().tissues().stream().anyMatch(value -> value.id().value().equals(id)
                    && value.capacity().enduranceJoules() > 0 && value.capacity().burstWatts() > 0);
        }
        // 吐纳动作由内容绑定；有恢复能力时必须声明占用，才能阻止有效吸呼期间恢复。
        boolean needsAction = body.structure().tissues().stream()
                .anyMatch(tissue -> (tissue.id().value().equals(profile.getRespiratoryTissueId())
                        || tissue.id().value().equals(profile.getAttentionTissueId())) && tissue.recoveryHalfLifeSeconds() > 0);
        if (profile.hasBodyAction()) {
            var reference = profile.getBodyAction();
            var definition = actions.find(reference.getId(), reference.getVersion()).orElse(null);
            valid &= definition != null;
            if (definition != null) {
                valid &= definition.getTissueIdsList().containsAll(
                        List.of(profile.getRespiratoryTissueId(), profile.getAttentionTissueId()));
                valid &= definition.getOrganIdsList().containsAll(organs);
            }
        } else {
            valid &= !needsAction;
        }
        var recipeIds = new HashSet<String>();
        for (String id : profile.getRecipeIdsList()) {
            valid &= recipeIds.add(id) && recipes.definitions().stream().anyMatch(recipe -> recipe.getId().equals(id));
        }
        for (BreathingOrgan organ : profile.getOrgansList()) {
            Anatomy.OrganDefinition definition = body.structure().organs().stream()
                    .filter(value -> value.id().value().equals(organ.getOrganId())).findFirst().orElse(null);
            valid &= definition != null && recipes.definitions().stream().anyMatch(recipe -> recipeIds.contains(recipe.getId())
                    && recipe.getOrganKind().equals(definition.kind().value()));
        }
        if (!valid) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_BREATHING);
        }
        for (int index = 0; index < 36; index++) {
            particles.add(spawn(index, 0));
            motions.add(new ParticleMotion(.012 + random.nextDouble() * .043, random.nextDouble() * Math.PI * 2));
        }
    }

    private static boolean positive(double value) {
        // 配置边界拒绝 NaN 与无限值，防止进入空气积分与疲劳预算。
        return Double.isFinite(value) && value > 0;
    }

    void control(BreathControl requested) {
        // 同序号重放不能重新开始或反复消费；迟到控制也不能覆盖新输入。
        if (requested.getSequence() <= control.getSequence()) {
            if (requested.equals(control)) {
                return;
            }
            throw MeridianErrors.rejectGathering(REQUEST_ID_CONFLICT);
        }
        long now = session.now();
        if (active && requested.getAction() == control.getAction()
                && (requested.getAction() == INHALE || requested.getAction() == EXHALE || requested.getAction() == HOLD)) {
            // 持续输入只刷新瞄准与租约；按键续期不能把 250 ms 摄入窗口切碎成网络包间隔。
            control = requested;
            leaseMillis = now + INPUT_LEASE_MILLIS;
            return;
        }
        if (active) {
            step(now);
        }
        if (requested.getAction() == BEGIN_BREATHING) {
            if (active) {
                throw MeridianErrors.rejectGathering(GATHERING_BUSY);
            }
            session.beginManual();
            BreathingView before = view(session.read());
            if (before.getCapacityLiters() <= 0 || Math.min(before.getStaminaRatio(), before.getAttentionRatio()) <= 0) {
                throw MeridianErrors.rejectGathering(MODULE_UNAVAILABLE);
            }
            active = true;
            failure = FAILURE_CODE_UNSPECIFIED;
            updatedMillis = now;
            status = "左键吸气 · 右键呼气";
        } else if (requested.getAction() == END_BREATHING) {
            stop();
        } else if (!active) {
            throw MeridianErrors.rejectGathering(INVALID_REQUEST);
        }
        // 按新输入切换后续区间的占用；此前气流已经由 step 按旧输入结清。
        BreathingView current = view(session.read());
        boolean usesBody = active && ((requested.getAction() == INHALE && airLiters < current.getCapacityLiters())
                || (requested.getAction() == EXHALE && airLiters > 0));
        updateBodyAction(usesBody);
        // 只有占用提交成功才接受新气流输入，失败后继续沿用旧控制状态。
        control = requested;
        leaseMillis = now + INPUT_LEASE_MILLIS;
        boundaryMillis = active ? now + STEP_MILLIS : 0;
    }

    boolean advance() {
        // 闲置会话没有唤醒；失去输入租约后停止主动操作，不积累离线吸收。
        if (!active) {
            return false;
        }
        try {
            long now = session.now();
            if (now < boundaryMillis) {
                return false;
            }
            step(now);
            boundaryMillis = active ? now + STEP_MILLIS : 0;
            return true;
        } catch (MeridianErrors.GatheringRejected failure) {
            stop();
            this.failure = failure.code();
            status = "吐纳已中止";
            return true;
        }
    }

    private void step(long now) {
        // 只处理有新输入保护的最近一个区间；服务器长暂停不补发整段资源。
        double seconds = Math.min(STEP_MILLIS, Math.max(0, now - updatedMillis)) / 1000.0;
        updatedMillis = now;
        if (seconds == 0) {
            return;
        }
        if (now > leaseMillis) {
            stop();
            status = "操作已暂停";
            return;
        }
        BodySnapshot body = session.read();
        BreathingView before = view(body);
        if (body.vitality().dead() || before.getCapacityLiters() <= 0) {
            stop();
            status = "呼吸器官无法工作";
            return;
        }
        BreathAction action = control.getAction();
        boolean inhaling = action == INHALE && airLiters < before.getCapacityLiters();
        boolean exhaling = action == EXHALE && airLiters > 0;
        if (!inhaling && !exhaling) {
            // 停气与肺容量边界只停止人体做功；环境里的游离灵气和残余气流仍会移动。
            updateBodyAction(false);
            session.settleManual();
            moveParticles(seconds, HOLD, before.getAirflowLitersPerSecond(), null);
            if (action == INHALE) {
                status = "肺已充满，请右键呼气";
            } else if (action == EXHALE) {
                status = "空气已排空，请左键吸气";
            }
            return;
        }

        // 空气边界和两份做功预算共同截短本次有效时长，不能靠最后一帧透支。
        Anatomy.TissueCapacity respiratory = available(body, profile.getRespiratoryTissueId());
        Anatomy.TissueCapacity attention = available(body, profile.getAttentionTissueId());
        double respiratoryWatts = Math.min(profile.getRespiratoryWatts(), respiratory.burstWatts());
        double attentionWatts = Math.min(profile.getAttentionWatts(), attention.burstWatts());
        if (Math.min(respiratoryWatts, attentionWatts) < .001
                || Math.min(respiratory.enduranceJoules(), attention.enduranceJoules()) < .001) {
            stop();
            status = "耐力或精力耗尽";
            return;
        }
        double airRoom = inhaling ? before.getCapacityLiters() - airLiters : airLiters;
        seconds = Math.min(seconds, airRoom / before.getAirflowLitersPerSecond());
        seconds = Math.min(seconds, respiratory.enduranceJoules() / respiratoryWatts);
        seconds = Math.min(seconds, attention.enduranceJoules() / attentionWatts);

        // 先收集命中候选；疲劳不足已在上面截断，最终所有身体命令仍经过私有策略。
        ExertTissues loads = ExertTissues.newBuilder()
                .addLoads(load(profile.getRespiratoryTissueId(), respiratoryWatts, seconds))
                .addLoads(load(profile.getAttentionTissueId(), attentionWatts, seconds)).build();
        BreathAction effectiveAction = inhaling ? INHALE : EXHALE;
        boolean captured = moveParticles(seconds, effectiveAction, before.getAirflowLitersPerSecond(), loads);
        if (!captured) {
            // 未吸到灵气也会疲劳；命中时则由摄入事务一并支付，不能重复扣费。
            session.settleManual();
            session.execute(BodyCommand.newBuilder().setExertTissues(loads));
        }
        double airChange = before.getAirflowLitersPerSecond() * seconds;
        airLiters = Math.max(0, Math.min(before.getCapacityLiters(), airLiters + (inhaling ? airChange : -airChange)));
        // 满吸或排空后解除占用，后续暂停时间可以恢复，不把整个打开窗口过程当成做功。
        updateBodyAction(inhaling ? airLiters < before.getCapacityLiters() : airLiters > 0);
        status = inhaling ? "吸气 · 引导空间气流汇入口鼻" : "呼气 · 引导空间气流向外扩散";
        BreathingView after = view(session.read());
        if (Math.min(after.getStaminaRatio(), after.getAttentionRatio()) < .00001) {
            stop();
            status = "耐力或精力耗尽";
        }
    }

    private static ExertTissue load(String id, double watts, double seconds) {
        // 呼吸与注意力按实际功率计费，零外部作用力不等于免费做功。
        return ExertTissue.newBuilder().setTissueId(id).setForceNewtons(0)
                .setPowerWatts(watts).setDurationSeconds(seconds).build();
    }

    private boolean moveParticles(double seconds, BreathAction action, double airflow, ExertTissues loads) {
        // 用连续的三维源汇场近似空气流动；所有灵气都受影响，距离和方向决定响应强弱。
        sceneSeconds += seconds;
        boolean inhaling = action == INHALE;
        double flowResponse = 1 - Math.exp(-seconds * 2.5);
        suction += ((inhaling ? 1 : 0) - suction) * flowResponse;
        blowing += ((action == EXHALE ? 1 : 0) - blowing) * flowResponse;
        if (action == INHALE || action == EXHALE) {
            double dx = control.getAimX() - .5;
            double dy = control.getAimY() - .4;
            double dz = (control.hasAimZ() ? control.getAimZ() : .5) - .5;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < .001) {
                dz = 1;
                length = 1;
            }
            // 空间气流逐渐转向新目标；松键后场强衰减，粒子惯性仍保留。
            flowX += (dx / length - flowX) * flowResponse;
            flowY += (dy / length - flowY) * flowResponse;
            flowZ += (dz / length - flowZ) * flowResponse;
            double directionLength = Math.sqrt(flowX * flowX + flowY * flowY + flowZ * flowZ);
            if (directionLength > .001) {
                flowX /= directionLength;
                flowY /= directionLength;
                flowZ /= directionLength;
            }
        }

        boolean captured = false;
        for (int index = 0; index < particles.size(); index++) {
            BreathParticle particle = particles.get(index);
            ParticleMotion motion = motions.get(index);
            double x = particle.getX(), y = particle.getY(), z = particle.getZ();

            // 温和的三轴游走有快有慢；拖曳降低旧速度，避免停气时突变位置。
            double relaxation = 1 - Math.exp(-seconds * .7);
            motion.vx += (Math.sin(sceneSeconds * .55 + motion.phase) * motion.speed - motion.vx) * relaxation;
            motion.vy += (Math.cos(sceneSeconds * .41 + motion.phase * 1.7) * motion.speed * .65 - motion.vy) * relaxation;
            motion.vz += (Math.sin(sceneSeconds * .47 + motion.phase * .8) * motion.speed - motion.vz) * relaxation;

            double offsetX = x - .5, offsetY = y - .4, offsetZ = z - .5;
            double distance = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
            if (distance > .001) {
                // 方向只加强主气流，背面和侧面保留弱响应；距离平方衰减避免远处瞬间吸附。
                double forward = Math.max(0, (offsetX * flowX + offsetY * flowY + offsetZ * flowZ) / distance);
                double directionWeight = .18 + .82 * forward * forward;
                double distanceWeight = 1 / (1 + distance * distance / (.22 * .22));
                double flowStrength = Math.min(.4, .18 + airflow * .06);
                double acceleration = (blowing - suction) * directionWeight * distanceWeight * flowStrength;
                motion.vx += offsetX / distance * acceleration * seconds;
                motion.vy += offsetY / distance * acceleration * seconds;
                motion.vz += offsetZ / distance * acceleration * seconds;
            }
            double speed = Math.sqrt(motion.vx * motion.vx + motion.vy * motion.vy + motion.vz * motion.vz);
            double speedScale = speed > .24 ? .24 / speed : 1;
            motion.vx *= speedScale;
            motion.vy *= speedScale;
            motion.vz *= speedScale;
            x += motion.vx * seconds;
            y += motion.vy * seconds;
            z += motion.vz * seconds;

            // 三维入口检查同时要求深度靠近；画面重叠不等于真实到达身体。
            double arrivalDistance = Math.sqrt(Math.pow(x - .5, 2) + Math.pow(y - .4, 2) + Math.pow(z - .5, 2));
            if (inhaling && arrivalDistance < .035 && !captured) {
                captured = tryCapture(particle.getAttribute(), loads);
                if (captured) {
                    particles.set(index, spawn(particle.getId(), particle.getGeneration() + 1));
                    motion.vx = motion.vy = motion.vz = 0;
                    continue;
                }
            }

            // 在局部观察区边缘缓慢折返，保留粒子身份和库存，不把吹出边界当作销毁。
            if (x < .03 || x > .97) {
                motion.vx *= -.6;
            }
            if (y < .03 || y > .97) {
                motion.vy *= -.6;
            }
            if (z < .03 || z > .97) {
                motion.vz *= -.6;
            }
            particles.set(index, particle.toBuilder().setX(clamp(x)).setY(clamp(y)).setZ(clamp(z)).build());
        }
        return captured;
    }

    private boolean tryCapture(String attribute, ExertTissues loads) {
        // 只从可信小批配方中挑选，左右或更多器官轮换；客户端不能直接声明捕获量。
        if (!session.canCaptureInterval(STEP_MILLIS)) {
            return false;
        }
        var choices = session.view().getChoicesList();
        for (int offset = 0; offset < profile.getOrgansCount(); offset++) {
            int organIndex = (lastOrgan + offset) % profile.getOrgansCount();
            String organId = profile.getOrgans(organIndex).getOrganId();
            for (GatheringChoice choice : choices) {
                if (!choice.getOrganId().equals(organId) || !profile.getRecipeIdsList().contains(choice.getRecipe().getId())
                        || choice.getRecipe().getInput().getEntriesCount() != 1
                        || !choice.getRecipe().getInput().getEntries(0).getAttribute().equals(attribute)
                        || GatheringChoices.total(choice.getRecipe().getOutput()) > choice.getTargetRemainingQi()) {
                    continue;
                }
                double input = GatheringChoices.total(choice.getRecipe().getInput());
                double elapsed = (session.now() - session.read().settledAt().elapsedMillis()) / 1000.0;
                if (input > elapsed * choice.getIntakePerSecond()) {
                    continue;
                }
                if (session.capture(choice, loads)) {
                    absorbedQi += input;
                    lastOrgan = (organIndex + 1) % profile.getOrgansCount();
                    return true;
                }
            }
        }
        return false;
    }

    private BreathParticle spawn(int id, int generation) {
        // 颜色按当前环境实有属性配比抽样；粒子只是机会标记，重生不补充环境库存。
        var inventory = session.view().getEnvironment().getQi().getEntriesList();
        double total = inventory.stream().mapToDouble(value -> value.getAmount()).sum();
        String attribute = "meridian:none";
        double pick = random.nextDouble() * total;
        for (var qi : inventory) {
            pick -= qi.getAmount();
            if (pick <= 0) {
                attribute = qi.getAttribute();
                break;
            }
        }
        double angle = random.nextDouble() * Math.PI * 2;
        double latitude = random.nextDouble() * 2 - 1;
        double radius = .12 + random.nextDouble() * .30;
        double horizontal = Math.sqrt(1 - latitude * latitude) * radius;
        return BreathParticle.newBuilder().setId(id).setGeneration(generation).setAttribute(attribute)
                .setX(.5 + Math.cos(angle) * horizontal).setY(clamp(.4 + latitude * radius))
                .setZ(.5 + Math.sin(angle) * horizontal).build();
    }

    private static double clamp(double value) {
        // 边缘折返后限制局部位置，仍可重新牵引，不制造新的资源。
        return Math.max(.03, Math.min(.97, value));
    }

    private static Anatomy.TissueCapacity available(BodySnapshot body, String id) {
        // 耐力直接读取受伤势与既有疲劳影响的实际组织能力。
        return TissueCapabilities.available(body, new Anatomy.TissueId(id));
    }

    BreathingView view(BodySnapshot body) {
        // 所有余量从唯一身体状态派生；窗口开关不会回填耐力或精力。
        double capacity = 0, airflow = 0;
        for (BreathingOrgan entry : profile.getOrgansList()) {
            var definition = body.structure().organs().stream()
                    .filter(value -> value.id().value().equals(entry.getOrganId())).findFirst().orElseThrow();
            var organ = body.organStates().stream().filter(value -> value.id().equals(definition.id())).findFirst().orElseThrow();
            var part = body.structure().parts().stream().filter(value -> value.id().equals(definition.partId())).findFirst().orElseThrow();
            var state = body.partStates().stream().filter(value -> value.id().equals(part.id())).findFirst().orElseThrow();
            double health = Math.min(organ.hp() / definition.maxHp(), state.hp() / part.maxHp());
            capacity += entry.getAirCapacityLiters() * health;
            airflow += entry.getAirflowLitersPerSecond() * health;
        }
        return BreathingView.newBuilder().setAvailable(true).setActive(active).setAction(control.getAction())
                .setAirLiters(Math.min(airLiters, capacity)).setCapacityLiters(capacity).setAirflowLitersPerSecond(airflow)
                .setStaminaRatio(ratio(body, profile.getRespiratoryTissueId()))
                .setAttentionRatio(ratio(body, profile.getAttentionTissueId()))
                .addAllOrganIds(profile.getOrgansList().stream().map(BreathingOrgan::getOrganId).toList())
                .addAllParticles(particles).setSequence(control.getSequence()).setStatus(status).setAbsorbedQi(absorbedQi)
                .setFailure(failure).build();
    }

    private static double ratio(BodySnapshot body, String id) {
        // 分母使用当前构型基础耐力，改造后不会继续套用出生数值。
        var definition = body.structure().tissues().stream().filter(value -> value.id().value().equals(id)).findFirst().orElseThrow();
        return available(body, id).enduranceJoules() / definition.capacity().enduranceJoules();
    }

    long nextBoundaryMillis() {
        // 宿主只需为正在吐纳的身体安排短区间事件。
        return boundaryMillis;
    }

    void stop() {
        // 先结束占用再停调度；提交失败保留重试机会，不能遗留没有唤醒的活动实例。
        updateBodyAction(false);
        active = false;
        suction = 0;
        blowing = 0;
        boundaryMillis = 0;
        status = "吐纳已结束";
    }

    private void updateBodyAction(boolean used) {
        // 动作引用在装配时验证；配置后只在状态切换提交一次启停。
        if (!profile.hasBodyAction() || (used && bodyActionInstance != null) || (!used && bodyActionInstance == null)) {
            return;
        }
        if (!used) {
            // 中止只解除本会话实例，允许其他动作先改变身体版本。
            session.stopManualAction(bodyActionInstance);
            bodyActionInstance = null;
            return;
        }
        session.settleManual();
        String instanceId = UUID.randomUUID().toString();
        session.execute(BodyCommand.newBuilder().setStartAction(StartAction.newBuilder().setInstanceId(instanceId)
                .setActionId(profile.getBodyAction().getId()).setActionVersion(profile.getBodyAction().getVersion())));
        bodyActionInstance = instanceId;
    }
}
