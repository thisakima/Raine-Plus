package ru.rainedev.raine.browser;

import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import java.util.random.RandomGenerator;

/**
 * Человеческий ввод: ни одного мгновенного, «роботизированного» действия.
 * <p>
 * Курсор летит к цели не по прямой и не в центр пиксель в пиксель — по
 * кривой Безье с естественной кинематикой: разгон, крейсерская скорость,
 * торможение, микрокоррекции, дрожь руки. Перед действием — реакционное
 * время с правильным распределением. Печатает с разной задержкой между
 * знаками, иногда делает опечатки и исправляет их. Листает колесом рывками
 * разной величины. Все паузы и движения подчиняются биологически правдоподобным
 * распределениям (log-normal, gamma, beta), а не равномерным.
 * <p>
 * Работает на потоке браузера (вызывается из {@link Browser#onPage}), так что
 * {@link Thread#sleep} здесь никого, кроме самого браузера, не тормозит.
 */
public final class HumanInput {

    private final RandomGenerator random;

    // Индивидуальные характеристики "этого пользователя" (генерируются один раз)
    private final double baseReactionTime;      // базовое реакционное время (мс)
    private final double mouseSpeedFactor;      // множитель скорости мыши
    private final double tremorAmplitude;       // амплитуда физиологического тремора (пкс)
    private final double correctionProbability; // вероятность микрокоррекции на пути
    private final double overshootTendency;     // склонность к перелёту цели
    private final double clickPressureVariation; // вариация удержания клика
    private final boolean isLeftHanded;         // влияет на кривизну траектории
    private final double fatigueRate;           // насколько быстро утомляется за сессию

    // Динамическое состояние сессии
    private double currentFatigue = 0.0;
    private long sessionStartTime;
    private int actionsCount = 0;

    public HumanInput(RandomGenerator random) {
        this.random = random;
        this.sessionStartTime = System.currentTimeMillis();

        // Генерируем индивидуальные характеристики (стабильны в пределах сессии)
        // Реакционное время: log-normal распределение, медиана ~250мс
        this.baseReactionTime = sampleLogNormal(5.5, 0.3); // ~250мс медиана
        // Скорость мыши: нормальное распределение вокруг 1.0
        this.mouseSpeedFactor = Math.clamp(random.nextGaussian() * 0.15 + 1.0, 0.6, 1.6);
        // Тремор: 0.1-0.5 пкс (физиологический тремор ~8-12 Гц)
        this.tremorAmplitude = 0.1 + random.nextDouble() * 0.4;
        // Вероятность коррекции: 15-35%
        this.correctionProbability = 0.15 + random.nextDouble() * 0.2;
        // Склонность к перелёту: 0.1-0.4
        this.overshootTendency = 0.1 + random.nextDouble() * 0.3;
        // Вариация удержания клика: 30-120мс
        this.clickPressureVariation = 30 + random.nextDouble() * 90;
        // Леворукость: ~10%
        this.isLeftHanded = random.nextDouble() < 0.1;
        // Утомляемость: как быстро растёт fatigue
        this.fatigueRate = 0.00005 + random.nextDouble() * 0.0001;
    }

    // ─────────────────────────────────────────────────────────────────
    // ВСПОМОГАТЕЛЬНЫЕ: биологически правдоподобные распределения
    // ─────────────────────────────────────────────────────────────────

    /** Log-normal: медиана = exp(mu), sigma = sigma */
    private double sampleLogNormal(double mu, double sigma) {
        return Math.exp(mu + sigma * random.nextGaussian());
    }

    /** Gamma distribution (shape=k, scale=theta) — для времени реакции */
    private double sampleGamma(double k, double theta) {
        // Marsaglia & Tsang method
        if (k < 1) {
            return sampleGamma(k + 1, theta) * Math.pow(random.nextDouble(), 1.0 / k);
        }
        double d = k - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x;
            double v;
            do {
                x = random.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = random.nextDouble();
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v * theta;
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) return d * v * theta;
        }
    }

    /** Beta distribution — для вероятностей */
    private double sampleBeta(double alpha, double beta) {
        double x = sampleGamma(alpha, 1.0);
        double y = sampleGamma(beta, 1.0);
        return x / (x + y);
    }

    /** Weibull — для времени удержания клика, пауз */
    private double sampleWeibull(double lambda, double k) {
        return lambda * Math.pow(-Math.log(random.nextDouble()), 1.0 / k);
    }

    /** Обновляет уровень утомления на основе времени сессии и количества действий */
    private void updateFatigue() {
        long sessionMs = System.currentTimeMillis() - sessionStartTime;
        currentFatigue = Math.min(1.0, fatigueRate * sessionMs + actionsCount * 0.001);
    }

    /** Получает множитель замедления от утомления (1.0 = норма, до 2.0 = сильно устал) */
    private double getFatigueMultiplier() {
        return 1.0 + currentFatigue;
    }

    // ─────────────────────────────────────────────────────────────────
    // ОСНОВНЫЕ МЕТОДЫ
    // ─────────────────────────────────────────────────────────────────

    /** Реакционное время с правильным распределением (gamma ~250мс медиана) */
    public void think() {
        updateFatigue();
        double base = sampleGamma(2.5, baseReactionTime / 2.5) * getFatigueMultiplier();
        sleep((long) Math.clamp(base, 80, 2000));
    }

    public void think(int minMs, int maxMs) {
        // fallback с равномерным, но с учётом утомления
        sleep((long) (between(minMs, maxMs) * getFatigueMultiplier()));
    }

    // ─────────────────────────────────────────────────────────────────
    // ДВИЖЕНИЕ МЫШИ: кривые Безье, кинематика, тремор, коррекции
    // ─────────────────────────────────────────────────────────────────

    /**
     * Двигает мышь к точке по естественной траектории:
     * - Кривая Безье 3-го порядка с 1-2 контрольными точками
     * - Профиль скорости: разгон → крейсерская → торможение (minimum jerk model)
     * - Физиологический тремор (8-12 Гц) на всём пути
     * - Вероятностные микрокоррекции на пути
     * - Перелёт цели и доводка (как у человека)
     */
    public void moveTo(Page page, double targetX, double targetY) {
        moveTo(page, targetX, targetY, null);
    }

    public void moveTo(Page page, double targetX, double targetY, BoundingBox constraintBox) {
        think(); // реакционное время перед движением

        double startX = getMouseX(page);
        double startY = getMouseY(page);

        // Если уже близко — просто дёргаем с тремором
        double dist = Math.hypot(targetX - startX, targetY - startY);
        if (dist < 5) {
            applyTremor(page, targetX, targetY, 50);
            return;
        }

        // Генерируем траекторию
        Trajectory traj = generateTrajectory(startX, startY, targetX, targetY, constraintBox);

        // Выполняем движение по точкам траектории
        executeTrajectory(page, traj);

        // Финальная доводка с тремором
        applyTremor(page, targetX, targetY, 100 + random.nextInt(200));

        actionsCount++;
    }

    /** Генерирует естественную траекторию движения */
    private Trajectory generateTrajectory(double sx, double sy, double tx, double ty, BoundingBox constraint) {
        double dist = Math.hypot(tx - sx, ty - sy);

        // Количество контрольных точек зависит от расстояния
        int controlPoints = dist < 100 ? 1 : (dist < 300 ? 2 : 3);

        // Базовая кривая Безье через случайные контрольные точки
        double[][] ctrlPoints = new double[controlPoints][2];
        for (int i = 0; i < controlPoints; i++) {
            double t = (i + 1) / (double) (controlPoints + 1);
            double cx = sx + (tx - sx) * t;
            double cy = sy + (ty - sy) * t;

            // Перпендикулярное смещение (кривизна траектории)
            double perpX = -(ty - sy) / dist;
            double perpY = (tx - sx) / dist;

            // Амплитуда изгиба: 5-30% от расстояния
            double curvature = (0.05 + random.nextDouble() * 0.25) * dist;
            // Леворукие искривляют в другую сторону
            if (isLeftHanded) curvature = -curvature;

            ctrlPoints[i][0] = cx + perpX * curvature;
            ctrlPoints[i][1] = cy + perpY * curvature;

            // Ограничиваем в пределах экрана/констрейнта
            if (constraint != null) {
                ctrlPoints[i][0] = clamp(ctrlPoints[i][0], constraint.x + 5, constraint.x + constraint.width - 5);
                ctrlPoints[i][1] = clamp(ctrlPoints[i][1], constraint.y + 5, constraint.y + constraint.height - 5);
            }
        }

        // Добавляем перелёт цели (overshoot)
        boolean willOvershoot = random.nextDouble() < overshootTendency;
        double overshootX = 0, overshootY = 0;
        if (willOvershoot) {
            double overshootDist = 5 + random.nextDouble() * 25;
            double angle = Math.atan2(ty - sy, tx - sx) + (random.nextDouble() - 0.5) * 0.5;
            overshootX = Math.cos(angle) * overshootDist;
            overshootY = Math.sin(angle) * overshootDist;
        }

        // Генерируем точки траектории с профилем скорости (minimum jerk)
        int numPoints = Math.max(10, Math.min(60, (int) (dist / 3)));
        double[] xs = new double[numPoints + 2];
        double[] ys = new double[numPoints + 2];
        long[] timestamps = new long[numPoints + 2];

        long startTime = System.nanoTime();

        for (int i = 0; i <= numPoints + 1; i++) {
            double t = i / (double) (numPoints + 1);

            // Smoothstep для плавного старта/финиша
            double smoothT = t * t * (3 - 2 * t);
            // Добавляем асимметрию: быстрее разгон, медленнее торможение
            if (t < 0.4) {
                smoothT = Math.pow(t / 0.4, 0.7) * 0.4;
            } else if (t > 0.7) {
                smoothT = 0.4 + 0.3 * Math.pow((t - 0.7) / 0.3, 1.5) + 0.3 * ((t - 0.7) / 0.3);
            }

            // Кривая Безье
            double x = bezierPoint(sx, tx, ctrlPoints, smoothT, true);
            double y = bezierPoint(sy, ty, ctrlPoints, smoothT, false);

            // Добавляем перелёт в конце
            if (i == numPoints + 1 && willOvershoot) {
                x += overshootX;
                y += overshootY;
            }

            // Физиологический тремор (8-12 Гц) — добавляем синусоиду
            double tremorPhase = t * 20 * Math.PI + random.nextDouble() * 2 * Math.PI;
            double tremorMag = tremorAmplitude * (0.5 + 0.5 * t); // растёт к концу
            x += Math.sin(tremorPhase) * tremorMag;
            y += Math.cos(tremorPhase * 1.3) * tremorMag;

            xs[i] = x;
            ys[i] = y;

            // Тайминг с профилем скорости (minimum jerk model)
            // v(t) ~ t^2 * (3-2t) производная smoothstep
            double velocity = 6 * t * (1 - t); // пик в середине
            double baseTime = dist / (800 * mouseSpeedFactor); // базовое время пропорционально расстоянию
            long pointTime = startTime + (long) (baseTime * 1e9 * (smoothT + 0.1 * velocity));
            timestamps[i] = pointTime;
        }

        return new Trajectory(xs, ys, timestamps);
    }

    /** Вычисляет точку на кривой Безье */
    private double bezierPoint(double start, double end, double[][] ctrl, double t, boolean isX) {
        int idx = isX ? 0 : 1;
        double result = start;
        double coeff = 1.0;

        // Bernstein polynomials для кубической Безье (упрощённый вариант)
        double u = 1 - t;
        result = u * u * u * start;
        result += 3 * u * u * t * ctrl[0][idx];
        if (ctrl.length > 1) {
            result += 3 * u * t * t * ctrl[1][idx];
        }
        result += t * t * t * end;
        return result;
    }

    /** Выполняет движение по предвычисленной траектории */
    private void executeTrajectory(Page page, Trajectory traj) {
        for (int i = 0; i < traj.xs.length; i++) {
            long targetTime = traj.timestamps[i];
            long now = System.nanoTime();
            long waitNs = targetTime - now;
            if (waitNs > 0) {
                sleepNs(waitNs);
            }

            page.mouse().move(traj.xs[i], traj.ys[i]);

            // Вероятностная микрокоррекция "на лету"
            if (i > 2 && i < traj.xs.length - 2 && random.nextDouble() < correctionProbability) {
                // Небольшая коррекция курса
                double corrX = (random.nextDouble() - 0.5) * 3;
                double corrY = (random.nextDouble() - 0.5) * 3;
                page.mouse().move(traj.xs[i] + corrX, traj.ys[i] + corrY);
                sleep(5 + random.nextInt(20));
            }
        }
    }

    /** Применяет физиологический тремор в финальной точке */
    private void applyTremor(Page page, double x, double y, int durationMs) {
        long start = System.currentTimeMillis();
        double phase = random.nextDouble() * 2 * Math.PI;
        while (System.currentTimeMillis() - start < durationMs) {
            double elapsed = (System.currentTimeMillis() - start) / 1000.0;
            double tremorFreq = 8 + random.nextDouble() * 4; // 8-12 Гц
            double tremor = tremorAmplitude * (1.0 + 0.3 * Math.sin(elapsed * 5)); // модуляция
            double tx = x + Math.sin(phase + elapsed * tremorFreq * 2 * Math.PI) * tremor;
            double ty = y + Math.cos(phase + elapsed * tremorFreq * 2 * Math.PI * 1.3) * tremor;
            page.mouse().move(tx, ty);
            sleep(16); // ~60 FPS
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // КЛИКИ, НАВЕДЕНИЯ, ВВОД
    // ─────────────────────────────────────────────────────────────────

    public void click(Page page, Locator target) {
        target.scrollIntoViewIfNeeded();
        sleep(between(90, 260) * (int) getFatigueMultiplier());
        BoundingBox box = target.boundingBox();
        if (box == null) {
            target.click();
            sleep((long) (between(140, 420) * getFatigueMultiplier()));
            return;
        }
        click(page, box);
    }

    public void click(Page page, BoundingBox box) {
        think();
        double[] target = pointIn(box);

        // Двигаемся к цели по естественной траектории
        moveTo(page, target[0], target[1], box);

        // Предкликовая пауза (фиксация взгляда)
        sleep((long) (sampleWeibull(120, 1.5) * getFatigueMultiplier()));

        // Клик с вариацией удержания
        double holdMs = sampleWeibull(clickPressureVariation, 1.2);
        page.mouse().down();
        sleep((long) Math.clamp(holdMs, 20, 300));
        page.mouse().up();

        // Посткликовая пауза
        sleep((long) (sampleWeibull(200, 1.3) * getFatigueMultiplier()));

        actionsCount++;
    }

    public void hover(Page page, Locator target) {
        target.scrollIntoViewIfNeeded();
        sleep((long) (between(80, 240) * getFatigueMultiplier()));
        BoundingBox box = target.boundingBox();
        if (box == null) {
            target.hover();
            sleep((long) (between(100, 300) * getFatigueMultiplier()));
            return;
        }
        double[] targetPoint = pointIn(box);
        moveTo(page, targetPoint[0], targetPoint[1], box);
        // Наведение дольше клика — человек рассматривает
        sleep((long) (between(200, 600) * getFatigueMultiplier()));
    }

    public void typeInto(Page page, Locator field, String text) {
        field.scrollIntoViewIfNeeded();
        think();
        BoundingBox box = field.boundingBox();
        if (box != null) {
            click(page, box);
        } else {
            field.click();
            sleep((long) (between(120, 320) * getFatigueMultiplier()));
        }
        field.fill("");
        sleep((long) (between(40, 130) * getFatigueMultiplier()));
        type(page, text);
    }

    public void type(Page page, String text) {
        if (text == null || text.isEmpty()) return;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            // Скорость печати с индивидуальным множителем и утомлением
            double baseDelay = sampleLogNormal(4.5, 0.4) * mouseSpeedFactor * getFatigueMultiplier(); // ~90мс медиана
            int delay = (int) Math.clamp(baseDelay, 25, 300);

            // Опечатки: ~2% шанс
            if (random.nextDouble() < 0.02 && i > 0 && i < text.length() - 1) {
                char wrong = (char) (ch + (random.nextBoolean() ? 1 : -1));
                page.keyboard().type(String.valueOf(wrong),
                        new Keyboard.TypeOptions().setDelay(delay));
                sleep((long) (sampleWeibull(150, 1.2) * getFatigueMultiplier()));
                // Backspace
                page.keyboard().press("Backspace");
                sleep((long) (sampleWeibull(100, 1.3) * getFatigueMultiplier()));
            }

            page.keyboard().type(String.valueOf(ch),
                    new Keyboard.TypeOptions().setDelay(delay));

            // Паузы на пунктуации/пробелах
            if (ch == ' ' || ch == ',' || ch == '.' || ch == '!' || ch == '?') {
                sleep((long) (sampleWeibull(200, 1.2) * getFatigueMultiplier()));
            } else if (random.nextDouble() < 0.06) {
                sleep((long) (sampleWeibull(250, 1.3) * getFatigueMultiplier()));
            }
        }
        sleep((long) (between(90, 280) * getFatigueMultiplier()));
    }

    public void scroll(Page page, int deltaY) {
        int sign = Integer.signum(deltaY);
        if (sign == 0) return;

        int remaining = Math.abs(deltaY);
        while (remaining > 0) {
            // Логнормальный шаг — человек прокручивает рывками разной длины
            int step = Math.min(remaining, (int) sampleLogNormal(5.0, 0.5));
            page.mouse().wheel(0, sign * step);
            remaining -= step;
            sleep((long) (sampleWeibull(120, 1.3) * getFatigueMultiplier()));
        }
        sleep((long) (between(140, 380) * getFatigueMultiplier()));
    }

    public void press(Page page, String key) {
        think((int) (120 * getFatigueMultiplier()), (int) (420 * getFatigueMultiplier()));
        page.keyboard().press(key);
        sleep((long) (between(120, 360) * getFatigueMultiplier()));
    }

    /**
     * Выбор опции в выпадающем списке: клик по dropdown, пауза, клик по option.
     */
    public void selectOption(Page page, Locator dropdown, String option) {
        dropdown.scrollIntoViewIfNeeded();
        think((int) (150 * getFatigueMultiplier()), (int) (500 * getFatigueMultiplier()));
        click(page, dropdown);
        sleep((long) (between(200, 600) * getFatigueMultiplier()));
        Locator item = dropdown.locator("option", new Locator.LocatorOptions().setHasText(option)).first();
        if (item.count() > 0) {
            click(page, item);
        } else {
            sleep((long) (between(100, 300) * getFatigueMultiplier()));
            dropdown.selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(option));
        }
        sleep((long) (between(150, 450) * getFatigueMultiplier()));
    }

    /**
     * Загрузка файла: клик по input, пауза, setInputFiles.
     */
    public void uploadFile(Page page, Locator input, java.nio.file.Path file) {
        input.scrollIntoViewIfNeeded();
        think((int) (200 * getFatigueMultiplier()), (int) (600 * getFatigueMultiplier()));
        BoundingBox box = input.boundingBox();
        if (box != null) {
            click(page, box);
        } else {
            input.click();
        }
        sleep((long) (between(300, 900) * getFatigueMultiplier()));
        input.setInputFiles(file);
        sleep((long) (between(400, 1200) * getFatigueMultiplier()));
    }

    /**
     * Скачивание: клик и ожидание download event.
     */
    public com.microsoft.playwright.Download clickAndWaitForDownload(Page page, Locator target) {
        target.scrollIntoViewIfNeeded();
        think((int) (150 * getFatigueMultiplier()), (int) (500 * getFatigueMultiplier()));
        return page.waitForDownload(() -> {
            BoundingBox box = target.boundingBox();
            if (box != null) {
                click(page, box);
            } else {
                target.click();
            }
        });
    }

    /**
     * Имитирует просмотр ленты видео: случайные паузы, движение мыши, скролл.
     */
    public void watchVideoFeed(Page page, int videosToWatch) {
        for (int i = 0; i < videosToWatch; i++) {
            // Смотрит ролик: пауза 3–15 секунд
            sleep((long) (sampleWeibull(5000, 1.5) * getFatigueMultiplier()));

            // Иногда (15%) двигает мышь в случайную точку рядом с центром
            if (random.nextDouble() < 0.15) {
                var vp = page.viewportSize();
                if (vp != null) {
                    double cx = vp.width / 2.0;
                    double cy = vp.height / 2.0;
                    double tx = clamp(cx + between(-200, 200), 10, vp.width - 10);
                    double ty = clamp(cy + between(-150, 150), 10, vp.height - 10);
                    moveTo(page, tx, ty);
                }
            }

            // Иногда (10%) микро-скролл вверх «пересмотреть»
            if (random.nextDouble() < 0.1) {
                scroll(page, -between(50, 150));
                sleep((long) (between(500, 2000) * getFatigueMultiplier()));
                scroll(page, between(50, 150));
            }

            // Листает на следующий ролик
            press(page, "ArrowDown");

            // Пауза между роликами
            sleep((long) (between(500, 3000) * getFatigueMultiplier()));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ВСПОМОГАТЕЛЬНЫЕ
    // ─────────────────────────────────────────────────────────────────

    private double getMouseX(Page page) {
        // Playwright не даёт получить текущую позицию мыши напрямую
        // В реальности мы бы отслеживали её сами. Здесь возвращаем центр viewport как fallback.
        var vp = page.viewportSize();
        return vp != null ? vp.width / 2.0 : 500;
    }

    private double getMouseY(Page page) {
        var vp = page.viewportSize();
        return vp != null ? vp.height / 2.0 : 500;
    }

    private double[] pointIn(BoundingBox box) {
        double centerX = box.x + box.width / 2;
        double centerY = box.y + box.height / 2;
        double jitterX = (random.nextDouble() - 0.5) * box.width * 0.3;
        double jitterY = (random.nextDouble() - 0.5) * box.height * 0.3;
        return new double[] {
                clamp(centerX + jitterX, box.x + 2, box.x + box.width - 2),
                clamp(centerY + jitterY, box.y + 2, box.y + box.height - 2)
        };
    }

    private int between(int min, int max) {
        return max <= min ? min : min + random.nextInt(max - min + 1);
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleepNs(long nanos) {
        if (nanos <= 0) return;
        try {
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ВНУТРЕННИЕ КЛАССЫ
    // ─────────────────────────────────────────────────────────────────

    private static class Trajectory {
        final double[] xs;
        final double[] ys;
        final long[] timestamps;

        Trajectory(double[] xs, double[] ys, long[] timestamps) {
            this.xs = xs;
            this.ys = ys;
            this.timestamps = timestamps;
        }
    }
}