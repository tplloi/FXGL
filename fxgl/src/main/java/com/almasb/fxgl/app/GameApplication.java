/*
 * The MIT License (MIT)
 *
 * FXGL - JavaFX Game Library
 *
 * Copyright (c) 2015-2017 AlmasB (almaslvl@gmail.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.almasb.fxgl.app;

import com.almasb.fxgl.asset.FXGLAssets;
import com.almasb.fxgl.core.concurrent.Async;
import com.almasb.fxgl.devtools.profiling.Profiler;
import com.almasb.fxgl.entity.GameWorld;
import com.almasb.fxgl.gameplay.GameState;
import com.almasb.fxgl.io.FXGLIO;
import com.almasb.fxgl.physics.PhysicsWorld;
import com.almasb.fxgl.saving.DataFile;
import com.almasb.fxgl.scene.DisplayEvent;
import com.almasb.fxgl.scene.GameScene;
import com.almasb.fxgl.scene.SceneFactory;
import com.almasb.fxgl.scene.menu.MenuEventListener;
import com.almasb.fxgl.service.Input;
import com.almasb.fxgl.service.impl.timer.FPSCounter;
import javafx.animation.AnimationTimer;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * To use FXGL extend this class and implement necessary methods.
 * The initialization process can be seen below (irrelevant phases are omitted):
 * <p>
 * <ol>
 * <li>Instance fields of YOUR subclass of GameApplication</li>
 * <li>initSettings()</li>
 * <li>Services configuration (after this you can safely call FXGL.getService())</li>
 * <li>initAchievements()</li>
 * <li>initInput()</li>
 * <li>preInit()</li>
 * <p>The following phases are NOT executed on UI thread</p>
 * <li>initAssets()</li>
 * <li>initGameVars()</li>
 * <li>initGame() OR loadState()</li>
 * <li>initPhysics()</li>
 * <li>initUI()</li>
 * <li>Start of main game loop execution on UI thread</li>
 * </ol>
 * <p>
 * Unless explicitly stated, methods are not thread-safe and must be
 * executed on the JavaFX Application (UI) Thread.
 * By default all callbacks are executed on the JavaFX Application (UI) Thread.
 * <p>
 *     Callback / listener notes: instance of GameApplication will always be
 *     notified last along the chain of callbacks.
 *     However, as per documentation, events are always fired after listeners.
 * </p>
 *
 * @author Almas Baimagambetov (AlmasB) (almaslvl@gmail.com)
 */
public abstract class GameApplication extends SimpleFXGLApplication {

    private AppStateMachine stateMachine;

    public final AppStateMachine getStateMachine() {
        return stateMachine;
    }

    private SceneFactory sceneFactory;

    public SceneFactory getSceneFactory() {
        return sceneFactory;
    }

    public void setSceneFactory(SceneFactory sceneFactory) {
        this.sceneFactory = sceneFactory;
    }

    @Override
    void configureApp() {
        log.debug("Configuring GameApplication");

        long start = System.nanoTime();

        setSceneFactory(initSceneFactory());
        stateMachine = new AppStateMachine(this);
        playState = (PlayState) stateMachine.getPlayState();

        registerServicesForUpdate();

        log.infof("Game configuration took:  %.3f sec", (System.nanoTime() - start) / 1000000000.0);

        Async.startFX(() -> {
            FXGL.getDisplay().initAndShow();

            // these things need to be called early before the main loop
            // so that menus can correctly display input controls, etc.
            // this is called once per application lifetime
            runPreInit();

            startMainLoop();
        });
    }

    private void registerServicesForUpdate() {
        addUpdateListener(getAudioPlayer());
    }

    private void runPreInit() {
        if (getSettings().isProfilingEnabled()) {
            profiler = new Profiler();
            profilerFont = FXGLAssets.UI_MONO_FONT.newFont(20);
        }

        FXGLIO.INSTANCE.setDefaultExceptionHandler(getExceptionHandler());
        FXGLIO.INSTANCE.setDefaultExecutor(getExecutor());

        initAchievements();

        // 1. register system actions
        SystemActions.INSTANCE.bind(getInput());

        // 2. register user actions
        initInput();

        // 3. scan for annotated methods and register them too
        getInput().scanForUserActions(this);

        preInit();

        getEventBus().addEventHandler(DisplayEvent.CLOSE_REQUEST, e -> exit());

        runTask(InitEventHandlersTask.class);
    }

    private AnimationTimer mainLoop;

    private void startMainLoop() {
        log.debug("Starting main loop");

        mainLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                long frameStart = System.nanoTime();

                double tpf = tickStart(now);

                tick(tpf);
                onPostUpdate(tpf);

                tickEnd(System.nanoTime() - frameStart);
            }
        };
        mainLoop.start();
    }

    /**
     * Only called in exceptional cases, e.g. uncaught (unchecked) exception.
     */
    void stopMainLoop() {
        log.debug("Stopping main loop");

        if (mainLoop != null)
            mainLoop.stop();
    }

    private ReadOnlyLongWrapper tick = new ReadOnlyLongWrapper();
    private ReadOnlyIntegerWrapper fps = new ReadOnlyIntegerWrapper();

    private FPSCounter fpsCounter = new FPSCounter();

    private double tickStart(long now) {
        tick.set(tick.get() + 1);

        fps.set(fpsCounter.update(now));

        // assume that fps is at least 5 to avoid subtle bugs
        // disregard minor fluctuations > 55 for smoother experience
        if (fps.get() < 5 || fps.get() > 55)
            fps.set(60);

        return 1.0 / fps.get();
    }

    private void tick(double tpf) {
        stateMachine.onUpdate(tpf);

        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onUpdate(tpf);
        }

        if (stateMachine.isInPlay()) {
            onUpdate(tpf);
        } else {
            onPausedUpdate(tpf);
        }
    }

    private Font profilerFont;
    private Profiler profiler;

    private void tickEnd(long frameTook) {
        if (getSettings().isProfilingEnabled()) {
            profiler.update(fps.get(), frameTook);

            GraphicsContext g = getGameScene().getGraphicsContext();
            g.setGlobalBlendMode(BlendMode.SRC_OVER);
            g.setGlobalAlpha(1);
            g.setFont(profilerFont);
            g.setFill(Color.RED);

            g.fillText(profiler.getInfo(), 0, getHeight() - 120.0);
        }
    }

    @Override
    protected final void exit() {
        if (getSettings().isProfilingEnabled()) {
            profiler.print();
        }

        super.exit();
    }

    //    @Override
//    public void pause() {
//        pushState(PauseSubState.INSTANCE);
//        super.pause();
//    }
//
//    @Override
//    public void resume() {
//        popState();
//        super.resume();
//    }

    /**
     * Initialize user application.
     */
    private void initApp(DataFile dataFile) {
        log.debug("Initializing App");

        FXGL.getInstance(LoadingState.class).setDataFile(dataFile);
        stateMachine.setState(ApplicationState.LOADING);
    }

    void startIntro() {
        stateMachine.setState(ApplicationState.INTRO);
    }

    void startGameMenu() {
        stateMachine.setState(ApplicationState.GAME_MENU);
    }

    void startMainMenu() {
        stateMachine.setState(ApplicationState.MAIN_MENU);
    }

    /**
     * Set state to PLAYING.
     */
    void startPlay() {
        stateMachine.setState(ApplicationState.PLAYING);
    }

    /**
     * (Re-)initializes the user application as new and starts the game.
     * Note: cannot be called during callbacks.
     */
    protected void startNewGame() {
        log.debug("Starting new game");
        initApp(DataFile.getEMPTY());
    }

    /**
     * (Re-)initializes the user application from the given data file and starts the game.
     * Note: cannot be called during callbacks.
     *
     * @param dataFile save data to load from
     */
    void startLoadedGame(DataFile dataFile) {
        log.debug("Starting loaded game");
        initApp(dataFile);
    }

    /**
     * Handler for menu events.
     */
    private MenuEventHandler menuHandler;

    /**
     * @return menu event handler associated with this game
     * @throws IllegalStateException if menus are not enabled
     */
    public MenuEventListener getMenuListener() {
        if (!getSettings().isMenuEnabled())
            throw new IllegalStateException("Menus are not enabled");

        if (menuHandler == null)
            menuHandler = new MenuEventHandler(this);
        return menuHandler;
    }

    private CopyOnWriteArrayList<UpdateListener> listeners = new CopyOnWriteArrayList<>();

    public final void addUpdateListener(UpdateListener listener) {
        listeners.add(listener);
    }

    public final void removeUpdateListener(UpdateListener listener) {
        listeners.remove(listener);
    }

    private PlayState playState;

    /**
     * @return game state
     */
    public final GameState getGameState() {
        return playState.getGameState();
    }

    /**
     * @return game world
     */
    public final GameWorld getGameWorld() {
        return playState.getGameWorld();
    }

    /**
     * @return physics world
     */
    public final PhysicsWorld getPhysicsWorld() {
        return playState.getPhysicsWorld();
    }

    /**
     * @return game scene
     */
    public final GameScene getGameScene() {
        return playState.getGameScene();
    }

    /**
     * @return game scene input
     */
    @Override
    public final Input getInput() {
        return playState.getInput();
    }

    @Override
    public final StateTimer getMasterTimer() {
        return playState.getTimer();
    }

    public final void addPlayStateListener(StateListener listener) {
        playState.addStateListener(listener);
    }

    public final void removePlayStateListener(StateListener listener) {
        playState.removeStateListener(listener);
    }
}
