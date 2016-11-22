/*
 * Copyright (C) 2016 The Android Open Source Project
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
package it.tailoradio.videomood.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import it.tailoradio.videomood.BuildConfig;
import it.tailoradio.videomood.R;

/**
 * Concatenates multiple {@link MediaSource}s. It is valid for the same {@link MediaSource} instance
 * to be present more than once in the concatenation.
 */
public final class AppendingMediaSource implements MediaSource {

    private final List<MediaSource> mediaSources;
    private final SparseArray<Timeline> timelines;
    private final SparseArray<Object> manifests;
    private final Map<MediaPeriod, Integer> sourceIndexByMediaPeriod;
    private SparseArray<Boolean> duplicateFlags;

    private Listener listener;
    private AppendingTimeline timeline;

    /**
     * @param mediaSources The {@link MediaSource}s to concatenate. It is valid for the same
     *                     {@link MediaSource} instance to be present more than once in the array.
     */
    public AppendingMediaSource(MediaSource... mediaSources) {
        this.mediaSources = new ArrayList<MediaSource>();
        for (MediaSource ms : mediaSources) {
            this.mediaSources.add(ms);
        }
//        timelines = new Timeline[mediaSources.length];
//        manifests = new Object[mediaSources.length];
        sourceIndexByMediaPeriod = new HashMap<MediaPeriod, Integer>();
//        duplicateFlags = buildDuplicateFlags(mediaSources);

        timelines = new SparseArray<Timeline>();
        manifests = new SparseArray<Object>();
        duplicateFlags = buildDuplicateFlags(this.mediaSources);

    }

    @Override
    public void prepareSource(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < mediaSources.size(); i++) {
            if (duplicateFlags == null || duplicateFlags.get(i) != null || !duplicateFlags.get(i)) {
                final int index = i;
                mediaSources.get(i).prepareSource(new Listener() {
                    @Override
                    public void onSourceInfoRefreshed(Timeline timeline, Object manifest) {
                        handleSourceInfoRefreshed(index, timeline, manifest);
                    }
                });
            }
        }
    }


    @Override
    public void maybeThrowSourceInfoRefreshError() throws IOException {
        for (int i = 0; i < mediaSources.size(); i++) {
            if (duplicateFlags == null || duplicateFlags.get(i) == null || !duplicateFlags.get(i)) {
                mediaSources.get(i).maybeThrowSourceInfoRefreshError();
            }
        }
    }

    @Override
    public MediaPeriod createPeriod(int index, Allocator allocator, long positionUs) {
        int sourceIndex = timeline.getSourceIndexForPeriod(index);
        int periodIndexInSource = index - timeline.getFirstPeriodIndexInSource(sourceIndex);
        MediaPeriod mediaPeriod = mediaSources.get(index).createPeriod(periodIndexInSource,
                allocator, positionUs);
        sourceIndexByMediaPeriod.put(mediaPeriod, sourceIndex);
        return mediaPeriod;
    }


    @Override
    public void releasePeriod(MediaPeriod mediaPeriod) {
        if(mediaPeriod != null && sourceIndexByMediaPeriod != null) {
            int sourceIndex = sourceIndexByMediaPeriod.get(mediaPeriod);
            sourceIndexByMediaPeriod.remove(mediaPeriod);
            mediaSources.get(sourceIndex).releasePeriod(mediaPeriod);
        }
    }

    @Override
    public void releaseSource() {
        for (int i = 0; i < mediaSources.size(); i++) {
            if (duplicateFlags == null || duplicateFlags.get(i) == null || !duplicateFlags.get(i)) {
                mediaSources.get(i).releaseSource();
            }
        }
    }


    public void release(){
        for(MediaSource ms : mediaSources){
            ms.releaseSource();
        }

        timelines.clear();
        manifests.clear();
        sourceIndexByMediaPeriod.clear();
        duplicateFlags.clear();
        System.gc();
    }

    private void handleSourceInfoRefreshed(int sourceFirstIndex, Timeline sourceTimeline,
                                           Object sourceManifest) {
        // Set the timeline and manifest.
        timelines.put(sourceFirstIndex, sourceTimeline);
        manifests.put(sourceFirstIndex, sourceManifest);
        // Also set the timeline and manifest for any duplicate entries of the same source.
        for (int i = sourceFirstIndex + 1; i < mediaSources.size(); i++) {
            if (mediaSources.get(i) == mediaSources.get(sourceFirstIndex)) {
                timelines.append(i, sourceTimeline);
                manifests.append(i, sourceManifest);
            }
        }
        for (int i = 0; i < timelines.size(); i++) {
            if (timelines.get(i) == null) {
                // Don't invoke the listener until all sources have timelines.
                return;
            }
        }
        timeline = new AppendingTimeline(timelines.clone());
        listener.onSourceInfoRefreshed(timeline, manifests.clone());
    }

    private static SparseArray<Boolean> buildDuplicateFlags(List<MediaSource> mediaSources) {
        SparseArray<Boolean> duplicateFlags = new SparseArray<Boolean>();
        IdentityHashMap<MediaSource, Void> sources = new IdentityHashMap<MediaSource, Void>(mediaSources.size());
        for (int i = 0; i < mediaSources.size(); i++) {
            MediaSource source = mediaSources.get(i);
            if (!sources.containsKey(source)) {
                sources.put(source, null);
                duplicateFlags.put(i, false);
            } else {
                duplicateFlags.put(i, true);
            }
        }
        return duplicateFlags;
    }

    /**
     * A {@link Timeline} that is the concatenation of one or more {@link Timeline}s.
     */
    private static final class AppendingTimeline extends Timeline {

        private final SparseArray<Timeline> timelines;
        private final int[] sourcePeriodOffsets;
        private final int[] sourceWindowOffsets;

        public AppendingTimeline(SparseArray<Timeline> timelines) {
            int[] sourcePeriodOffsets = new int[timelines.size()];
            int[] sourceWindowOffsets = new int[timelines.size()];
            int periodCount = 0;
            int windowCount = 0;
            for (int i = 0; i < timelines.size(); i++) {
                Timeline timeline = timelines.get(i);
                periodCount += timeline.getPeriodCount();
                sourcePeriodOffsets[i] = periodCount;
                windowCount += timeline.getWindowCount();
                sourceWindowOffsets[i] = windowCount;
            }
            this.timelines = timelines;
            this.sourcePeriodOffsets = sourcePeriodOffsets;
            this.sourceWindowOffsets = sourceWindowOffsets;
        }

        @Override
        public int getWindowCount() {
            return sourceWindowOffsets[sourceWindowOffsets.length - 1];
        }

        @Override
        public Window getWindow(int windowIndex, Window window, boolean setIds) {
            int sourceIndex = getSourceIndexForWindow(windowIndex);
            int firstWindowIndexInSource = getFirstWindowIndexInSource(sourceIndex);
            int firstPeriodIndexInSource = getFirstPeriodIndexInSource(sourceIndex);
            timelines.get(sourceIndex).getWindow(windowIndex - firstWindowIndexInSource, window,
                    setIds);
            window.firstPeriodIndex += firstPeriodIndexInSource;
            window.lastPeriodIndex += firstPeriodIndexInSource;
            return window;
        }

        @Override
        public int getPeriodCount() {
            return sourcePeriodOffsets[sourcePeriodOffsets.length - 1];
        }

        @Override
        public Period getPeriod(int periodIndex, Period period, boolean setIds) {
            int sourceIndex = getSourceIndexForPeriod(periodIndex);
            int firstWindowIndexInSource = getFirstWindowIndexInSource(sourceIndex);
            int firstPeriodIndexInSource = getFirstPeriodIndexInSource(sourceIndex);
            timelines.get(sourceIndex).getPeriod(periodIndex - firstPeriodIndexInSource, period,
                    setIds);
            period.windowIndex += firstWindowIndexInSource;
            if (setIds) {
                period.uid = Pair.create(sourceIndex, period.uid);
            }
            return period;
        }

        @Override
        public int getIndexOfPeriod(Object uid) {
            if (!(uid instanceof Pair)) {
                return C.INDEX_UNSET;
            }
            Pair<?, ?> sourceIndexAndPeriodId = (Pair<?, ?>) uid;
            if (!(sourceIndexAndPeriodId.first instanceof Integer)) {
                return C.INDEX_UNSET;
            }
            int sourceIndex = (Integer) sourceIndexAndPeriodId.first;
            Object periodId = sourceIndexAndPeriodId.second;
            if (sourceIndex < 0 || sourceIndex >= timelines.size()) {
                return C.INDEX_UNSET;
            }
            int periodIndexInSource = timelines.get(sourceIndex).getIndexOfPeriod(periodId);
            return periodIndexInSource == C.INDEX_UNSET ? C.INDEX_UNSET
                    : getFirstPeriodIndexInSource(sourceIndex) + periodIndexInSource;
        }

        private int getSourceIndexForPeriod(int periodIndex) {
            return Util.binarySearchFloor(sourcePeriodOffsets, periodIndex, true, false) + 1;
        }

        private int getFirstPeriodIndexInSource(int sourceIndex) {
            return sourceIndex == 0 ? 0 : sourcePeriodOffsets[sourceIndex - 1];
        }

        private int getSourceIndexForWindow(int windowIndex) {
            return Util.binarySearchFloor(sourceWindowOffsets, windowIndex, true, false) + 1;
        }

        private int getFirstWindowIndexInSource(int sourceIndex) {
            return sourceIndex == 0 ? 0 : sourceWindowOffsets[sourceIndex - 1];
        }

    }

    //    <editor-fold desc="Custom Methods">

    public void appendSource(MediaSource mediaSource) {
        if(BuildConfig.DEBUG){
            Log.d(GlobalStrings.DEBUG_TAG_ACT, "appending new source... line 277");
        }
        this.mediaSources.add(mediaSource);
        if(this.mediaSources.size() > 2){
            removeFirstMediaSource();
        }
        duplicateFlags = buildDuplicateFlags(this.mediaSources);
        if (!mediaSources.isEmpty())
            prepareSource(mediaSources.size() - 1);
        else
            prepareSource(0);
    }

    public void appendSource(String uriString, Context mContext){
        if(BuildConfig.DEBUG){
            Log.d(GlobalStrings.DEBUG_TAG_ACT, "appending new source... line 289");
        }
        appendSource(buildMediaSource(uriString, mContext));
    }

    private void prepareSource(final int index) {

        mediaSources.get(index).prepareSource(new Listener() {
            @Override
            public void onSourceInfoRefreshed(Timeline timeline, Object manifest) {
                if(BuildConfig.DEBUG){
                    Log.d(GlobalStrings.DEBUG_TAG_ACT, "appending new source: prepared... " +
                            index);
                }
                handleSourceInfoRefreshed(index, timeline, manifest);
            }
        });

    }

    private void removeFirstMediaSource(){
        MediaSource ds = this.mediaSources.get(0);
        ds.releaseSource();
        this.mediaSources.remove(0);
        this.timelines.remove(0);
        this.manifests.remove(0);
    }

    private MediaSource buildMediaSource(String uriString, Context mContext){
        // Measures bandwidth during playback. Can be null if not required.
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
        // Produces DataSource instances through which media data is loaded.
        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(mContext,
                Util.getUserAgent(mContext, mContext.getString(R.string.app_name)),
                bandwidthMeter);
        // Produces Extractor instances for parsing the media data.
        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
        // This is the MediaSource representing the media to be played.
        return new ExtractorMediaSource(
                Uri.parse(uriString),
                dataSourceFactory, extractorsFactory, null, null);
    }

    //    </editor-fold>



}
