/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.quickstep.logging;

import static androidx.core.util.Preconditions.checkNotNull;
import static androidx.core.util.Preconditions.checkState;
import static com.android.launcher3.logger.LauncherAtom.ContainerInfo.ContainerCase.EXTENDED_CONTAINERS;
import static com.android.launcher3.logger.LauncherAtom.ContainerInfo.ContainerCase.FOLDER;
import static com.android.launcher3.logger.LauncherAtom.ContainerInfo.ContainerCase.SEARCH_RESULT_CONTAINER;
import static com.android.launcher3.logger.LauncherAtomExtensions.ExtendedContainers.ContainerCase.DEVICE_SEARCH_RESULT_CONTAINER;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_WORKSPACE_SNAPSHOT;
import static com.android.systemui.shared.system.SysUiStatsLog.LAUNCHER_UICHANGED__DST_STATE__ALLAPPS;
import static com.android.systemui.shared.system.SysUiStatsLog.LAUNCHER_UICHANGED__DST_STATE__BACKGROUND;
import static com.android.systemui.shared.system.SysUiStatsLog.LAUNCHER_UICHANGED__DST_STATE__HOME;
import static com.android.systemui.shared.system.SysUiStatsLog.LAUNCHER_UICHANGED__DST_STATE__OVERVIEW;

import android.app.slice.SliceItem;
import android.content.Context;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.Utilities;
import com.android.launcher3.logger.LauncherAtom;
import com.android.launcher3.logger.LauncherAtom.ContainerInfo;
import com.android.launcher3.logger.LauncherAtom.FolderContainer.ParentContainerCase;
import com.android.launcher3.logger.LauncherAtom.FolderIcon;
import com.android.launcher3.logger.LauncherAtom.FromState;
import com.android.launcher3.logger.LauncherAtom.ToState;
import com.android.launcher3.logger.LauncherAtomExtensions.DeviceSearchResultContainer;
import com.android.launcher3.logger.LauncherAtomExtensions.ExtendedContainers;
import com.android.launcher3.logging.InstanceId;
import com.android.launcher3.logging.StatsLogManager;
import com.android.launcher3.model.AllAppsList;
import com.android.launcher3.model.BaseModelUpdateTask;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.LogConfig;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;
import com.android.systemui.shared.system.SysUiStatsLog;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This class calls StatsLog compile time generated methods.
 * <p>
 * To see if the logs are properly sent to statsd, execute following command.
 * <ul>
 * $ wwdebug (to turn on the logcat printout)
 * $ wwlogcat (see logcat with grep filter on)
 * $ statsd_testdrive (see how ww is writing the proto to statsd buffer)
 * </ul>
 */
public class StatsLogCompatManager extends StatsLogManager {

    private static final String TAG = "StatsLog";
    private static final boolean IS_VERBOSE = Utilities.isPropertyEnabled(LogConfig.STATSLOG);
    private static final InstanceId DEFAULT_INSTANCE_ID = InstanceId.fakeInstanceId(0);
    // LauncherAtom.ItemInfo.getDefaultInstance() should be used but until launcher proto migrates
    // from nano to lite, bake constant to prevent robo test failure.
    private static final int DEFAULT_PAGE_INDEX = -2;
    private static final int FOLDER_HIERARCHY_OFFSET = 100;
    private static final int SEARCH_RESULT_HIERARCHY_OFFSET = 200;
    private static final int EXTENDED_CONTAINERS_HIERARCHY_OFFSET = 300;
    private static final int ATTRIBUTE_MULTIPLIER = 100;

    public static final CopyOnWriteArrayList<StatsLogConsumer> LOGS_CONSUMER =
            new CopyOnWriteArrayList<>();

    private final Context mContext;

    public StatsLogCompatManager(Context context) {
        mContext = context;
    }

    @Override
    protected StatsLogger createLogger() {
        return new StatsCompatLogger(mContext);
    }

    /**
     * Synchronously writes an itemInfo to stats log
     */
    @WorkerThread
    public static void writeSnapshot(LauncherAtom.ItemInfo info, InstanceId instanceId) {
        if (IS_VERBOSE) {
            Log.d(TAG, String.format("\nwriteSnapshot(%d):\n%s", instanceId.getId(), info));
        }
        if (!Utilities.ATLEAST_R) {
            return;
        }
        SysUiStatsLog.write(SysUiStatsLog.LAUNCHER_SNAPSHOT,
                LAUNCHER_WORKSPACE_SNAPSHOT.getId() /* event_id */,
                info.getAttribute().getNumber() * ATTRIBUTE_MULTIPLIER
                        + info.getItemCase().getNumber()  /* target_id */,
                instanceId.getId() /* instance_id */,
                0 /* uid */,
                getPackageName(info) /* package_name */,
                getComponentName(info) /* component_name */,
                getGridX(info, false) /* grid_x */,
                getGridY(info, false) /* grid_y */,
                getPageId(info) /* page_id */,
                getGridX(info, true) /* grid_x_parent */,
                getGridY(info, true) /* grid_y_parent */,
                getParentPageId(info) /* page_id_parent */,
                getHierarchy(info) /* hierarchy */,
                info.getIsWork() /* is_work_profile */,
                info.getAttribute().getNumber() /* origin */,
                getCardinality(info) /* cardinality */,
                info.getWidget().getSpanX(),
                info.getWidget().getSpanY(),
                getFeatures(info));
    }

    /**
     * Helps to construct and write statsd compatible log message.
     */
    private static class StatsCompatLogger implements StatsLogger {

        private static final ItemInfo DEFAULT_ITEM_INFO = new ItemInfo();

        private Context mContext;
        private ItemInfo mItemInfo = DEFAULT_ITEM_INFO;
        private InstanceId mInstanceId = DEFAULT_INSTANCE_ID;
        private OptionalInt mRank = OptionalInt.empty();
        private Optional<ContainerInfo> mContainerInfo = Optional.empty();
        private int mSrcState = LAUNCHER_STATE_UNSPECIFIED;
        private int mDstState = LAUNCHER_STATE_UNSPECIFIED;
        private Optional<FromState> mFromState = Optional.empty();
        private Optional<ToState> mToState = Optional.empty();
        private Optional<String> mEditText = Optional.empty();
        private SliceItem mSliceItem;
        private LauncherAtom.Slice mSlice;

        StatsCompatLogger(Context context) {
            mContext = context;
        }

        @Override
        public StatsLogger withItemInfo(ItemInfo itemInfo) {
            if (mContainerInfo.isPresent()) {
                throw new IllegalArgumentException(
                        "ItemInfo and ContainerInfo are mutual exclusive; cannot log both.");
            }
            this.mItemInfo = itemInfo;
            return this;
        }

        @Override
        public StatsLogger withInstanceId(InstanceId instanceId) {
            this.mInstanceId = instanceId;
            return this;
        }

        @Override
        public StatsLogger withRank(int rank) {
            this.mRank = OptionalInt.of(rank);
            return this;
        }

        @Override
        public StatsLogger withSrcState(int srcState) {
            this.mSrcState = srcState;
            return this;
        }

        @Override
        public StatsLogger withDstState(int dstState) {
            this.mDstState = dstState;
            return this;
        }

        @Override
        public StatsLogger withContainerInfo(ContainerInfo containerInfo) {
            checkState(mItemInfo == DEFAULT_ITEM_INFO,
                    "ItemInfo and ContainerInfo are mutual exclusive; cannot log both.");
            this.mContainerInfo = Optional.of(containerInfo);
            return this;
        }

        @Override
        public StatsLogger withFromState(FromState fromState) {
            this.mFromState = Optional.of(fromState);
            return this;
        }

        @Override
        public StatsLogger withToState(ToState toState) {
            this.mToState = Optional.of(toState);
            return this;
        }

        @Override
        public StatsLogger withEditText(String editText) {
            this.mEditText = Optional.of(editText);
            return this;
        }

        @Override
        public StatsLogger withSliceItem(@NonNull SliceItem sliceItem) {
            checkState(mItemInfo == DEFAULT_ITEM_INFO && mSlice == null,
                    "ItemInfo, Slice and SliceItem are mutual exclusive; cannot set more than one"
                            + " of them.");
            this.mSliceItem = checkNotNull(sliceItem, "expected valid sliceItem but received null");
            return this;
        }

        @Override
        public StatsLogger withSlice(LauncherAtom.Slice slice) {
            checkState(mItemInfo == DEFAULT_ITEM_INFO && mSliceItem == null,
                    "ItemInfo, Slice and SliceItem are mutual exclusive; cannot set more than one"
                            + " of them.");
            checkNotNull(slice, "expected valid slice but received null");
            checkNotNull(slice.getUri(), "expected valid slice uri but received null");
            this.mSlice = slice;
            return this;
        }

        @Override
        public void log(EventEnum event) {
            if (!Utilities.ATLEAST_R) {
                return;
            }
            LauncherAppState appState = LauncherAppState.getInstanceNoCreate();

            if (mSlice == null && mSliceItem != null) {
                mSlice = LauncherAtom.Slice.newBuilder().setUri(
                        mSliceItem.getSlice().getUri().toString()).build();
            }

            if (mSlice != null) {
                Executors.MODEL_EXECUTOR.execute(
                        () -> {
                            LauncherAtom.ItemInfo.Builder itemInfoBuilder =
                                    LauncherAtom.ItemInfo.newBuilder().setSlice(mSlice);
                            mContainerInfo.ifPresent(itemInfoBuilder::setContainerInfo);
                            write(event, applyOverwrites(itemInfoBuilder.build()));
                        });
                return;
            }

            if (mItemInfo.container < 0 || appState == null) {
                // Write log on the model thread so that logs do not go out of order
                // (for eg: drop comes after drag)
                Executors.MODEL_EXECUTOR.execute(
                        () -> write(event, applyOverwrites(mItemInfo.buildProto())));
            } else {
                // Item is inside the folder, fetch folder info in a BG thread
                // and then write to StatsLog.
                appState.getModel().enqueueModelUpdateTask(
                        new BaseModelUpdateTask() {
                            @Override
                            public void execute(LauncherAppState app, BgDataModel dataModel,
                                                AllAppsList apps) {
                                FolderInfo folderInfo = dataModel.folders.get(mItemInfo.container);
                                write(event, applyOverwrites(mItemInfo.buildProto(folderInfo)));
                            }
                        });
            }
        }

        @Override
        public void sendToInteractionJankMonitor(EventEnum event, View view) {
            if (!(event instanceof LauncherEvent)) {
                return;
            }
            switch ((LauncherEvent) event) {
                case LAUNCHER_ALLAPPS_VERTICAL_SWIPE_BEGIN:
                    InteractionJankMonitorWrapper.begin(
                            view,
                            InteractionJankMonitorWrapper.CUJ_ALL_APPS_SCROLL);
                    break;
                case LAUNCHER_ALLAPPS_VERTICAL_SWIPE_END:
                    InteractionJankMonitorWrapper.end(
                            InteractionJankMonitorWrapper.CUJ_ALL_APPS_SCROLL);
                    break;
                default:
                    break;
            }
        }

        private LauncherAtom.ItemInfo applyOverwrites(LauncherAtom.ItemInfo atomInfo) {
            LauncherAtom.ItemInfo.Builder itemInfoBuilder = atomInfo.toBuilder();

            mRank.ifPresent(itemInfoBuilder::setRank);
            mContainerInfo.ifPresent(itemInfoBuilder::setContainerInfo);

            if (mFromState.isPresent() || mToState.isPresent() || mEditText.isPresent()) {
                FolderIcon.Builder folderIconBuilder = itemInfoBuilder
                        .getFolderIcon()
                        .toBuilder();
                mFromState.ifPresent(folderIconBuilder::setFromLabelState);
                mToState.ifPresent(folderIconBuilder::setToLabelState);
                mEditText.ifPresent(folderIconBuilder::setLabelInfo);
                itemInfoBuilder.setFolderIcon(folderIconBuilder);
            }
            return itemInfoBuilder.build();
        }

        @WorkerThread
        private void write(EventEnum event, LauncherAtom.ItemInfo atomInfo) {
            InstanceId instanceId = mInstanceId;
            int srcState = mSrcState;
            int dstState = mDstState;
            if (IS_VERBOSE) {
                String name = (event instanceof Enum) ? ((Enum) event).name() :
                        event.getId() + "";

                Log.d(TAG, instanceId == DEFAULT_INSTANCE_ID
                        ? String.format("\n%s (State:%s->%s)\n%s", name, getStateString(srcState),
                        getStateString(dstState), atomInfo)
                        : String.format("\n%s (State:%s->%s) (InstanceId:%s)\n%s", name,
                        getStateString(srcState), getStateString(dstState), instanceId,
                        atomInfo));
            }

            for (StatsLogConsumer consumer : LOGS_CONSUMER) {
                consumer.consume(event, atomInfo);
            }

            SysUiStatsLog.write(
                    SysUiStatsLog.LAUNCHER_EVENT,
                    SysUiStatsLog.LAUNCHER_UICHANGED__ACTION__DEFAULT_ACTION /* deprecated */,
                    srcState,
                    dstState,
                    null /* launcher extensions, deprecated */,
                    false /* quickstep_enabled, deprecated */,
                    event.getId() /* event_id */,
                    atomInfo.getAttribute().getNumber() * ATTRIBUTE_MULTIPLIER
                            + atomInfo.getItemCase().getNumber() /* target_id */,
                    instanceId.getId() /* instance_id TODO */,
                    0 /* uid TODO */,
                    getPackageName(atomInfo) /* package_name */,
                    getComponentName(atomInfo) /* component_name */,
                    getGridX(atomInfo, false) /* grid_x */,
                    getGridY(atomInfo, false) /* grid_y */,
                    getPageId(atomInfo) /* page_id */,
                    getGridX(atomInfo, true) /* grid_x_parent */,
                    getGridY(atomInfo, true) /* grid_y_parent */,
                    getParentPageId(atomInfo) /* page_id_parent */,
                    getHierarchy(atomInfo) /* hierarchy */,
                    atomInfo.getIsWork() /* is_work_profile */,
                    atomInfo.getRank() /* rank */,
                    atomInfo.getFolderIcon().getFromLabelState().getNumber() /* fromState */,
                    atomInfo.getFolderIcon().getToLabelState().getNumber() /* toState */,
                    atomInfo.getFolderIcon().getLabelInfo() /* edittext */,
                    getCardinality(atomInfo) /* cardinality */,
                    getFeatures(atomInfo) /* features */);
        }
    }

    private static int getCardinality(LauncherAtom.ItemInfo info) {
        switch (info.getContainerInfo().getContainerCase()) {
            case PREDICTED_HOTSEAT_CONTAINER:
                return info.getContainerInfo().getPredictedHotseatContainer().getCardinality();
            case SEARCH_RESULT_CONTAINER:
                return info.getContainerInfo().getSearchResultContainer().getQueryLength();
            case EXTENDED_CONTAINERS:
                ExtendedContainers extendedCont = info.getContainerInfo().getExtendedContainers();
                if (extendedCont.getContainerCase() == DEVICE_SEARCH_RESULT_CONTAINER) {
                    DeviceSearchResultContainer deviceSearchResultCont = extendedCont
                            .getDeviceSearchResultContainer();
                    return deviceSearchResultCont.hasQueryLength() ? deviceSearchResultCont
                            .getQueryLength() : -1;
                }
            default:
                return info.getFolderIcon().getCardinality();
        }
    }

    private static String getPackageName(LauncherAtom.ItemInfo info) {
        switch (info.getItemCase()) {
            case APPLICATION:
                return info.getApplication().getPackageName();
            case SHORTCUT:
                return info.getShortcut().getShortcutName();
            case WIDGET:
                return info.getWidget().getPackageName();
            case TASK:
                return info.getTask().getPackageName();
            case SEARCH_ACTION_ITEM:
                return info.getSearchActionItem().getPackageName();
            default:
                return null;
        }
    }

    private static String getComponentName(LauncherAtom.ItemInfo info) {
        switch (info.getItemCase()) {
            case APPLICATION:
                return info.getApplication().getComponentName();
            case SHORTCUT:
                return info.getShortcut().getShortcutName();
            case WIDGET:
                return info.getWidget().getComponentName();
            case TASK:
                return info.getTask().getComponentName();
            case SEARCH_ACTION_ITEM:
                return info.getSearchActionItem().getTitle();
            case SLICE:
                return info.getSlice().getUri();
            default:
                return null;
        }
    }

    private static int getGridX(LauncherAtom.ItemInfo info, boolean parent) {
        if (info.getContainerInfo().getContainerCase() == FOLDER) {
            if (parent) {
                return info.getContainerInfo().getFolder().getWorkspace().getGridX();
            } else {
                return info.getContainerInfo().getFolder().getGridX();
            }
        } else {
            return info.getContainerInfo().getWorkspace().getGridX();
        }
    }

    private static int getGridY(LauncherAtom.ItemInfo info, boolean parent) {
        if (info.getContainerInfo().getContainerCase() == FOLDER) {
            if (parent) {
                return info.getContainerInfo().getFolder().getWorkspace().getGridY();
            } else {
                return info.getContainerInfo().getFolder().getGridY();
            }
        } else {
            return info.getContainerInfo().getWorkspace().getGridY();
        }
    }

    private static int getPageId(LauncherAtom.ItemInfo info) {
        if (info.hasTask()) {
            return info.getTask().getIndex();
        }
        switch (info.getContainerInfo().getContainerCase()) {
            case FOLDER:
                return info.getContainerInfo().getFolder().getPageIndex();
            case HOTSEAT:
                return info.getContainerInfo().getHotseat().getIndex();
            case PREDICTED_HOTSEAT_CONTAINER:
                return info.getContainerInfo().getPredictedHotseatContainer().getIndex();
            default:
                return info.getContainerInfo().getWorkspace().getPageIndex();
        }
    }

    private static int getParentPageId(LauncherAtom.ItemInfo info) {
        switch (info.getContainerInfo().getContainerCase()) {
            case FOLDER:
                if (info.getContainerInfo().getFolder().getParentContainerCase()
                        == ParentContainerCase.HOTSEAT) {
                    return info.getContainerInfo().getFolder().getHotseat().getIndex();
                }
                return info.getContainerInfo().getFolder().getWorkspace().getPageIndex();
            case SEARCH_RESULT_CONTAINER:
                return info.getContainerInfo().getSearchResultContainer().getWorkspace()
                        .getPageIndex();
            default:
                return info.getContainerInfo().getWorkspace().getPageIndex();
        }
    }

    private static int getHierarchy(LauncherAtom.ItemInfo info) {
        if (info.getContainerInfo().getContainerCase() == FOLDER) {
            return info.getContainerInfo().getFolder().getParentContainerCase().getNumber()
                    + FOLDER_HIERARCHY_OFFSET;
        } else if (info.getContainerInfo().getContainerCase() == SEARCH_RESULT_CONTAINER) {
            return info.getContainerInfo().getSearchResultContainer().getParentContainerCase()
                    .getNumber() + SEARCH_RESULT_HIERARCHY_OFFSET;
        } else if (info.getContainerInfo().getContainerCase() == EXTENDED_CONTAINERS) {
            return info.getContainerInfo().getExtendedContainers().getContainerCase().getNumber()
                    + EXTENDED_CONTAINERS_HIERARCHY_OFFSET;
        } else {
            return info.getContainerInfo().getContainerCase().getNumber();
        }
    }

    private static String getStateString(int state) {
        switch (state) {
            case LAUNCHER_UICHANGED__DST_STATE__BACKGROUND:
                return "BACKGROUND";
            case LAUNCHER_UICHANGED__DST_STATE__HOME:
                return "HOME";
            case LAUNCHER_UICHANGED__DST_STATE__OVERVIEW:
                return "OVERVIEW";
            case LAUNCHER_UICHANGED__DST_STATE__ALLAPPS:
                return "ALLAPPS";
            default:
                return "INVALID";
        }
    }

    private static int getFeatures(LauncherAtom.ItemInfo info) {
        if (info.getItemCase().equals(LauncherAtom.ItemInfo.ItemCase.WIDGET)) {
            return info.getWidget().getWidgetFeatures();
        }
        return 0;
    }


    /**
     * Interface to get stats log while it is dispatched to the system
     */
    public interface StatsLogConsumer {

        @WorkerThread
        void consume(EventEnum event, LauncherAtom.ItemInfo atomInfo);
    }
}
