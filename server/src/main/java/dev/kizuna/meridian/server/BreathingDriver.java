package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
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
    private static final double MOUTH_RADIUS = .08;
    private final GatheringSession<C> session;
    private final BreathingProfile profile;
    private final List<BreathParticle> particles = new ArrayList<>();
    private final List<ParticleMotion> motions = new ArrayList<>();
    private final Random random = new Random();
    private BreathControl control = BreathControl.newBuilder().setAction(HOLD).setAimX(.5).setAimY(.4).build();
    private boolean active;
    private boolean automatic;
    private BreathAction automaticAction = INHALE;
    private long updatedMillis;
    private long leaseMillis;
    private long boundaryMillis;
    private double airLiters;
    private double absorbedQi;
    private double storedQi;
    private String status = "准备吐纳";
    private FailureCode failure = FAILURE_CODE_UNSPECIFIED;
    private int lastOrgan;
    private int nextParticleId;
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
                && profile.getRecipeIdsCount() > 0 && positive(profile.getParticleVolumeBlocks());
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
        if (profile.hasAutomatic()) {
            // 自动能力必须有独立动作，避免免收费却继续占用精力、阻止其恢复。
            var automaticProfile = profile.getAutomatic();
            var reference = automaticProfile.getBodyAction();
            var definition = actions.find(reference.getId(), reference.getVersion()).orElse(null);
            valid &= positive(automaticProfile.getMinimumStaminaRatio()) && automaticProfile.getMinimumStaminaRatio() <= 1
                    && positive(automaticProfile.getCaptureEfficiency()) && automaticProfile.getCaptureEfficiency() < 1;
            valid &= definition != null;
            if (definition != null) {
                valid &= definition.getTissueIdsList().equals(List.of(profile.getRespiratoryTissueId()))
                        && new HashSet<>(definition.getOrganIdsList()).equals(organs);
            }
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
        refreshParticles();
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
        boolean changesMode = requested.getAction() == BEGIN_BREATHING || requested.getAction() == SET_AUTOMATIC_BREATHING;
        if (changesMode && requested.getAutomatic()) {
            if (!profile.hasAutomatic()) {
                throw MeridianErrors.rejectGathering(INTAKE_UNSUPPORTED);
            }
            if (view(session.read()).getStaminaRatio() < profile.getAutomatic().getMinimumStaminaRatio()) {
                throw MeridianErrors.rejectGathering(MODULE_UNAVAILABLE);
            }
        }
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
            if (before.getCapacityLiters() <= 0 || before.getStaminaRatio() <= 0
                    || !requested.getAutomatic() && before.getAttentionRatio() <= 0) {
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
        if (changesMode) {
            // 先结清旧模式，再切换占用；自动吸呼只由实际空气边界选择方向。
            if (requested.getAutomatic()
                    && view(session.read()).getStaminaRatio() < profile.getAutomatic().getMinimumStaminaRatio()) {
                throw MeridianErrors.rejectGathering(MODULE_UNAVAILABLE);
            }
            updateBodyAction(false);
            automatic = requested.getAutomatic();
            automaticAction = airLiters >= view(session.read()).getCapacityLiters() ? EXHALE : INHALE;
        }
        // 按新输入切换后续区间的占用；此前气流已经由 step 按旧输入结清。
        BreathingView current = view(session.read());
        boolean usesBody = active && (automatic || (requested.getAction() == INHALE && airLiters < current.getCapacityLiters())
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
        if (!automatic && now > leaseMillis) {
            stop();
            status = "操作已暂停";
            return;
        }
        // 接触摄入受阻可以自行恢复；每段重新观察，不能让旧失败永久遮住后续成功。
        failure = FAILURE_CODE_UNSPECIFIED;
        BodySnapshot body = session.read();
        BreathingView before = view(body);
        if (body.vitality().dead() || before.getCapacityLiters() <= 0) {
            stop();
            status = "呼吸器官无法工作";
            return;
        }
        if (automatic && before.getStaminaRatio() < profile.getAutomatic().getMinimumStaminaRatio()) {
            stop();
            status = "呼吸耐力低于自动停止线，请休息后重新开始";
            return;
        }
        if (automatic && airLiters >= before.getCapacityLiters()) {
            automaticAction = EXHALE;
        } else if (automatic && airLiters <= 0) {
            automaticAction = INHALE;
        }
        BreathAction action = automatic ? automaticAction : control.getAction();
        boolean inhaling = action == INHALE && airLiters < before.getCapacityLiters();
        boolean exhaling = action == EXHALE && airLiters > 0;
        if (!inhaling && !exhaling) {
            // 停气与肺容量边界只停止人体做功；环境里的游离灵气和残余气流仍会移动。
            updateBodyAction(false);
            session.settleManual();
            moveParticles(seconds, HOLD, before.getAirflowLitersPerSecond());
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
        if (respiratoryWatts < .001 || respiratory.enduranceJoules() < .001
                || !automatic && (attentionWatts < .001 || attention.enduranceJoules() < .001)) {
            stop();
            status = "耐力或精力耗尽";
            return;
        }
        double airRoom = inhaling ? before.getCapacityLiters() - airLiters : airLiters;
        seconds = Math.min(seconds, airRoom / before.getAirflowLitersPerSecond());
        seconds = Math.min(seconds, respiratory.enduranceJoules() / respiratoryWatts);
        if (!automatic) {
            seconds = Math.min(seconds, attention.enduranceJoules() / attentionWatts);
        }

        // 每段呼吸做功只支付一次；同段多颗接触独立入账，不再按捕获次数重复收取疲劳。
        var loads = ExertTissues.newBuilder().addLoads(load(profile.getRespiratoryTissueId(), respiratoryWatts, seconds));
        if (!automatic) {
            loads.addLoads(load(profile.getAttentionTissueId(), attentionWatts, seconds));
        }
        BreathAction effectiveAction = inhaling ? INHALE : EXHALE;
        session.settleManual();
        session.execute(BodyCommand.newBuilder().setExertTissues(loads));
        moveParticles(seconds, effectiveAction, before.getAirflowLitersPerSecond());
        double airChange = before.getAirflowLitersPerSecond() * seconds;
        airLiters = Math.max(0, Math.min(before.getCapacityLiters(), airLiters + (inhaling ? airChange : -airChange)));
        // 满吸或排空后解除占用，后续暂停时间可以恢复，不把整个打开窗口过程当成做功。
        updateBodyAction(automatic || (inhaling ? airLiters < before.getCapacityLiters() : airLiters > 0));
        status = inhaling ? "吸气 · 引导空间气流汇入口鼻" : "呼气 · 引导空间气流向外扩散";
        BreathingView after = view(session.read());
        if (automatic && after.getStaminaRatio() < profile.getAutomatic().getMinimumStaminaRatio()) {
            stop();
            status = "呼吸耐力低于自动停止线，请休息后重新开始";
        } else if (after.getStaminaRatio() < .00001 || !automatic && after.getAttentionRatio() < .00001) {
            stop();
            status = "耐力或精力耗尽";
        }
    }

    private static ExertTissue load(String id, double watts, double seconds) {
        // 呼吸与注意力按实际功率计费，零外部作用力不等于免费做功。
        return ExertTissue.newBuilder().setTissueId(id).setForceNewtons(0)
                .setPowerWatts(watts).setDurationSeconds(seconds).build();
    }

    private void moveParticles(double seconds, BreathAction action, double airflow) {
        // 用连续的三维源汇场近似空气流动；所有灵气都受影响，距离和方向决定响应强弱。
        List<Qi> inventory = refreshParticles();
        sceneSeconds += seconds;
        boolean inhaling = action == INHALE;
        double flowResponse = 1 - Math.exp(-seconds * 2.5);
        suction += ((inhaling ? 1 : 0) - suction) * flowResponse;
        blowing += ((action == EXHALE ? 1 : 0) - blowing) * flowResponse;
        if (action == INHALE || action == EXHALE) {
            // 自动采用固定正前方引导，同样经过气流积分与真实入口碰撞。
            double dx = automatic ? 0 : control.getAimX() - .5;
            double dy = automatic ? 0 : control.getAimY() - .4;
            double dz = automatic ? 1 : (control.hasAimZ() ? control.getAimZ() : .5) - .5;
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

            // 入口附近直接接收；整段轨迹都参与检测，更新跨过口鼻也不会漏掉。
            // 逐颗处理同段所有命中，只有真实库存与存储空间决定本次能接收多少。
            if (inhaling && touchesMouth(particle.getX(), particle.getY(), particle.getZ(), x, y, z)) {
                if (tryCapture(particle.getAttribute())) {
                    particles.set(index, spawn(particle.getId(), particle.getGeneration() + 1, inventory));
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
    }

    static boolean touchesMouth(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        // 求入口到运动线段的最短距离，兼容静止粒子、起点已在范围内和高速穿过。
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double squaredLength = dx * dx + dy * dy + dz * dz;
        double along = 0;
        if (squaredLength > 0) {
            along = ((.5 - fromX) * dx + (.4 - fromY) * dy + (.5 - fromZ) * dz) / squaredLength;
            along = Math.max(0, Math.min(1, along));
        }
        double offsetX = fromX + along * dx - .5;
        double offsetY = fromY + along * dy - .4;
        double offsetZ = fromZ + along * dz - .5;
        return offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ <= MOUTH_RADIUS * MOUTH_RADIUS;
    }

    private boolean tryCapture(String attribute) {
        // 配方只提供转换比例，剂量由当前浓度计算；器官轮换且无需等待上一批运输。
        var choices = session.view().getChoicesList();
        FailureCode captureFailure = FAILURE_CODE_UNSPECIFIED;
        for (int offset = 0; offset < profile.getOrgansCount(); offset++) {
            int organIndex = (lastOrgan + offset) % profile.getOrgansCount();
            String organId = profile.getOrgans(organIndex).getOrganId();
            for (GatheringChoice choice : choices) {
                if (!choice.getOrganId().equals(organId) || !profile.getRecipeIdsList().contains(choice.getRecipe().getId())
                        || choice.getRecipe().getInput().getEntriesCount() != 1
                        || dev.kizuna.meridian.protocol.QiDensity.phase(choice.getRecipe().getInput().getEntries(0).getDensity()) != QiPhase.FREE
                        || !choice.getRecipe().getInput().getEntries(0).getAttribute().equals(attribute)) {
                    continue;
                }
                try {
                    double samplingVolume = profile.getParticleVolumeBlocks();
                    if (automatic) {
                        samplingVolume *= profile.getAutomatic().getCaptureEfficiency();
                    }
                    absorbedQi += session.capture(choice, samplingVolume);
                    lastOrgan = (organIndex + 1) % profile.getOrgansCount();
                    failure = FAILURE_CODE_UNSPECIFIED;
                    return true;
                } catch (MeridianErrors.GatheringRejected rejection) {
                    // 一个器官满了就尝试其他入口；没收到的粒子保留，等待后续空间或来源变化。
                    if (rejection.code() != CAPACITY_EXCEEDED && rejection.code() != MODULE_UNAVAILABLE
                            && rejection.code() != INSUFFICIENT_QI && rejection.code() != ENVIRONMENT_UNAVAILABLE
                            && rejection.code() != ENVIRONMENT_INTERVAL_INVALID) {
                        throw rejection;
                    }
                    captureFailure = rejection.code();
                }
            }
        }
        failure = captureFailure;
        return false;
    }

    void recordStoredQi(double amount) {
        // 仅由运输完成边界累计炼化产物；捕获原料或开始加工不等于经脉已经收货。
        storedQi += amount;
    }

    private List<Qi> refreshParticles() {
        // 机会标记只代表当前器官可炼化的游离灵气；损耗释放物不能挤占可操作粒子。
        var view = session.view();
        List<String> organIds = profile.getOrgansList().stream().map(BreathingOrgan::getOrganId).toList();
        List<String> attributes = view.getChoicesList().stream()
                .filter(choice -> organIds.contains(choice.getOrganId()))
                .filter(choice -> profile.getRecipeIdsList().contains(choice.getRecipe().getId()))
                .map(GatheringChoice::getRecipe)
                .filter(recipe -> recipe.getInput().getEntriesCount() == 1)
                .map(recipe -> recipe.getInput().getEntries(0))
                .filter(qi -> dev.kizuna.meridian.protocol.QiDensity.phase(qi.getDensity()) == QiPhase.FREE)
                .map(Qi::getAttribute).distinct().toList();
        List<Qi> inventory = view.getEnvironment().getQi().getEntriesList().stream()
                .filter(qi -> dev.kizuna.meridian.protocol.QiDensity.phase(qi.getDensity()) == QiPhase.FREE && qi.getAmount() > 0)
                .filter(qi -> attributes.contains(qi.getAttribute())).toList();
        List<String> availableAttributes = inventory.stream().map(Qi::getAttribute).toList();

        // 来源耗尽或改换后移除失效标记，运动数据随同删除；不凭空补出不存在的属性。
        for (int index = particles.size() - 1; index >= 0; index--) {
            if (!availableAttributes.contains(particles.get(index).getAttribute())) {
                particles.remove(index);
                motions.remove(index);
            }
        }
        if (!inventory.isEmpty()) {
            while (particles.size() < 36) {
                particles.add(spawn(nextParticleId++, 0, inventory));
                motions.add(new ParticleMotion(.012 + random.nextDouble() * .043, random.nextDouble() * Math.PI * 2));
            }
        }
        return inventory;
    }

    private BreathParticle spawn(int id, int generation, List<Qi> inventory) {
        // 颜色按可炼化原料的实际配比抽样；粒子重生只提供接触机会，不补充环境库存。
        double total = inventory.stream().mapToDouble(Qi::getAmount).sum();
        String attribute = inventory.get(inventory.size() - 1).getAttribute();
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
        return BreathingView.newBuilder().setAvailable(true).setActive(active)
                .setAction(automatic && active ? automaticAction : control.getAction()).setAutomatic(automatic)
                .setAirLiters(Math.min(airLiters, capacity)).setCapacityLiters(capacity).setAirflowLitersPerSecond(airflow)
                .setStaminaRatio(ratio(body, profile.getRespiratoryTissueId()))
                .setAttentionRatio(ratio(body, profile.getAttentionTissueId()))
                // 自动参数来自可信内容声明，客户端只显示并不能提交覆盖值。
                .setAutomaticMinimumStaminaRatio(profile.getAutomatic().getMinimumStaminaRatio())
                .setAutomaticCaptureEfficiency(profile.getAutomatic().getCaptureEfficiency())
                .addAllOrganIds(profile.getOrgansList().stream().map(BreathingOrgan::getOrganId).toList())
                .addAllParticles(particles).setSequence(control.getSequence()).setStatus(status).setAbsorbedQi(absorbedQi)
                .setStoredQi(storedQi)
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
        automatic = false;
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
        var reference = automatic ? profile.getAutomatic().getBodyAction() : profile.getBodyAction();
        session.execute(BodyCommand.newBuilder().setStartAction(StartAction.newBuilder().setInstanceId(instanceId)
                .setActionId(reference.getId()).setActionVersion(reference.getVersion())));
        bodyActionInstance = instanceId;
    }
}
