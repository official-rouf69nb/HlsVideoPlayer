package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.EventListener;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class VideoPlayer {
  private static final String FORMAT_SS = "ss";
  private static final String FORMAT_DASH = "dash";
  private static final String FORMAT_HLS = "hls";
  private static final String FORMAT_OTHER = "other";

  private SimpleExoPlayer exoPlayer;
  DefaultTrackSelector trackSelector;
  DefaultBandwidthMeter bandwidthMeter;

  private Surface surface;

  private final TextureRegistry.SurfaceTextureEntry textureEntry;

  private QueuingEventSink eventSink = new QueuingEventSink();

  private final EventChannel eventChannel;

  private boolean isInitialized = false;

  private final VideoPlayerOptions options;

  int selectedHeight = 0;
  int rendererIndex = -1;
  JSONArray jsonArray;


  VideoPlayer(
      Context context,
      EventChannel eventChannel,
      TextureRegistry.SurfaceTextureEntry textureEntry,
      String dataSource,
      String formatHint,
      VideoPlayerOptions options) {
    this.eventChannel = eventChannel;
    this.textureEntry = textureEntry;
    this.options = options;


    //======= BandwidthMeter =======
    Uri uri = Uri.parse(dataSource);
    jsonArray = new JSONArray();
    bandwidthMeter = new DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(400000).build();

    //=======TrackSelector & LoadControl=======
    TrackSelection.Factory trackSelectorFactory = new AdaptiveTrackSelection.Factory(2000, 2000, 2000, .8f);
    LoadControl loadControl = new DefaultLoadControl.Builder().setBufferDurationsMs(7000, 10000, 1000, 2000).createDefaultLoadControl();
    trackSelector = new DefaultTrackSelector(context,trackSelectorFactory);

    if(Util.inferContentType(uri.getLastPathSegment())==C.TYPE_HLS){
      exoPlayer = new SimpleExoPlayer.Builder(context)
              .setLoadControl(loadControl)
              .setBandwidthMeter(bandwidthMeter)
              .setTrackSelector(trackSelector)
              .build();
    }else{
      trackSelector = new DefaultTrackSelector(context);
      exoPlayer = new SimpleExoPlayer.Builder(context)
              .setTrackSelector(trackSelector)
              .build();
    }

    DataSource.Factory dataSourceFactory;
    if(Util.inferContentType(uri.getLastPathSegment())==C.TYPE_HLS){
      DefaultHttpDataSourceFactory httpDataSourceFactory = new DefaultHttpDataSourceFactory("ExoDemoPlayer", bandwidthMeter, DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS, DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS, true);
      dataSourceFactory = new DefaultDataSourceFactory(context, bandwidthMeter, httpDataSourceFactory);
      HlsMediaSource.Factory mediaSourceFactory = new HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(true);
      MediaSource mediaSource =  mediaSourceFactory.createMediaSource(uri);
      exoPlayer.addVideoListener(new VideoListener() {
        @Override
        public void onVideoSizeChanged(int width, int height, int unAppliedRotationDegrees, float pixelWidthHeightRatio) {
          selectedHeight = height;
        }
      });
      exoPlayer.prepare(mediaSource);
    }
    else if (isHTTP(uri)) {
      dataSourceFactory = new DefaultHttpDataSourceFactory("ExoPlayer", null, DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS, DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS, true);
      MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint, context);
      exoPlayer.prepare(mediaSource);
    }
    else {
      dataSourceFactory = new DefaultDataSourceFactory(context, "ExoPlayer");
      MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint, context);
      exoPlayer.prepare(mediaSource);
    }

    setupVideoPlayer(eventChannel, textureEntry);
  }

  private static boolean isHTTP(Uri uri) {
    if (uri == null || uri.getScheme() == null) {
      return false;
    }
    String scheme = uri.getScheme();
    return scheme.equals("http") || scheme.equals("https");
  }

  private MediaSource buildMediaSource(Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint, Context context) {
    int type;
    if (formatHint == null) {
      type = Util.inferContentType(uri.getLastPathSegment());
    } else {
      switch (formatHint) {
        case FORMAT_SS:
          type = C.TYPE_SS;
          break;
        case FORMAT_DASH:
          type = C.TYPE_DASH;
          break;
        case FORMAT_HLS:
          type = C.TYPE_HLS;
          break;
        case FORMAT_OTHER:
          type = C.TYPE_OTHER;
          break;
        default:
          type = -1;
          break;
      }
    }
    switch (type) {
      case C.TYPE_SS:
        return new SsMediaSource.Factory(
                new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
            .createMediaSource(uri);
      case C.TYPE_DASH:
        return new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
            .createMediaSource(uri);
      case C.TYPE_HLS:
        return new HlsMediaSource.Factory(mediaDataSourceFactory).createMediaSource(uri);
      case C.TYPE_OTHER:
        return new ExtractorMediaSource.Factory(mediaDataSourceFactory)
            .setExtractorsFactory(new DefaultExtractorsFactory())
            .createMediaSource(uri);
      default:
        {
          throw new IllegalStateException("Unsupported type: " + type);
        }
    }
  }

  private void setupVideoPlayer(EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry) {
    eventChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink sink) {
            eventSink.setDelegate(sink);
          }

          @Override
          public void onCancel(Object o) {
            eventSink.setDelegate(null);
          }
        });

    surface = new Surface(textureEntry.surfaceTexture());
    exoPlayer.setVideoSurface(surface);
    setAudioAttributes(exoPlayer, options.mixWithOthers);

    exoPlayer.addListener(
        new EventListener() {

          @Override
          public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
            if (playbackState == Player.STATE_BUFFERING) {
              sendBufferingUpdate();
            } else if (playbackState == Player.STATE_READY) {
              if (!isInitialized) {
                isInitialized = true;
                sendInitialized();
                _resolveMediaTracks();
              }
            } else if (playbackState == Player.STATE_ENDED) {
              Map<String, Object> event = new HashMap<>();
              event.put("event", "completed");
              eventSink.success(event);
            }
          }

          @Override
          public void onPlayerError(final ExoPlaybackException error) {
            if (eventSink != null) {
              eventSink.error("VideoError", "Video player had error " + error, null);
            }
          }

          @Override
          public void onTracksChanged(TrackGroupArray trackGps, TrackSelectionArray trackSelections) {
            MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
            if(mappedTrackInfo != null) {
              for (int rendererIndex = 0; rendererIndex < mappedTrackInfo.getRendererCount(); rendererIndex++) {
                TrackGroupArray trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex);
                if (trackGroups.length != 0) {
                  if (exoPlayer.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
                    DefaultTrackSelector.SelectionOverride selectionOverride = trackSelector.getParameters().getSelectionOverride(0,trackGroups);
                    if(selectionOverride != null) {
                      for (int groupIndex = 0; groupIndex < trackGroups.length; groupIndex++) {
                        TrackGroup group = trackGroups.get(groupIndex);
                        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                          if (selectionOverride.tracks[0] == trackIndex) {
                            selectedHeight = group.getFormat(trackIndex).height;
                          }
                        }
                      }
                    }
                    break;
                  }
                }
              }
            }
          }
        });
  }

  void sendBufferingUpdate() {
    Map<String, Object> event = new HashMap<>();
    event.put("event", "bufferingUpdate");
    List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
    // iOS supports a list of buffered ranges, so here is a list with a single range.
    event.put("values", Collections.singletonList(range));
    eventSink.success(event);
  }

  private void _resolveMediaTracks(){
    try {
      MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
      if(mappedTrackInfo != null) {
        for (int rendererIndex = 0; rendererIndex < mappedTrackInfo.getRendererCount(); rendererIndex++) {
          TrackGroupArray trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex);
          if (trackGroups.length != 0) {
            if (exoPlayer.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
              for (int groupIndex = 0; groupIndex < trackGroups.length; groupIndex++) {
                TrackGroup trackGroup = trackGroups.get(groupIndex);
                for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                  Format track = trackGroup.getFormat(trackIndex);
                  JSONObject obj = new JSONObject();
                  obj.put("rendererIndex",rendererIndex);
                  obj.put("groupIndex",groupIndex);
                  obj.put("trackIndex",trackIndex);
                  obj.put("height",track.height);
                  obj.put("width",track.width);
                  obj.put("bitrate",track.bitrate);
                  jsonArray.put(obj);
                }
              }
              break;
            }
          }
        }
      }
    }catch(Exception ignored){ }
  }

  @SuppressWarnings("deprecation")
  private static void setAudioAttributes(SimpleExoPlayer exoPlayer, boolean isMixMode) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      exoPlayer.setAudioAttributes(
          new AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build(), !isMixMode);
    } else {
      exoPlayer.setAudioStreamType(C.STREAM_TYPE_MUSIC);
    }
  }

  void play() {
    exoPlayer.setPlayWhenReady(true);
  }

  void pause() {
    exoPlayer.setPlayWhenReady(false);
  }

  void setLooping(boolean value) {
    exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
  }

  void setVolume(double value) {
    float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
    exoPlayer.setVolume(bracketedValue);
  }

  void seekTo(int location) {
    exoPlayer.seekTo(location);
  }

  long getPosition() {
    return exoPlayer.getCurrentPosition();
  }
  String getMediaTracks(){
    if(selectedHeight > 0 && jsonArray != null && jsonArray.length() >0){
      JSONObject obj = new JSONObject();
      try {
        obj.put("rendererIndex",rendererIndex);
        obj.put("selectedHeight",selectedHeight);
        obj.put("tracks",jsonArray.toString());
      } catch (JSONException e) {
        return null;
      }
      return  obj.toString();
    }else{
      return null;
    }
  }

  void setMediaTrack(int rendererIndex, int groupIndex, int trackIndex){
    try {
      MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
      if(mappedTrackInfo != null) {
        DefaultTrackSelector.ParametersBuilder builder = trackSelector.getParameters().buildUpon();
        for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
          builder.clearSelectionOverrides(i);
        }

        if(rendererIndex > -1) {
          int[] tracks = {trackIndex};
          int reason = 2;
          int data = 0;
          DefaultTrackSelector.SelectionOverride override = new DefaultTrackSelector.SelectionOverride(groupIndex, tracks, reason, data);
          builder.setSelectionOverride(rendererIndex, mappedTrackInfo.getTrackGroups(rendererIndex), override);
        }
        this.rendererIndex = rendererIndex;
        trackSelector.setParameters(builder.build());
      }
    }catch (Exception ignored){}
  }

  @SuppressWarnings("SuspiciousNameCombination")
  private void sendInitialized() {
    if (isInitialized) {
      Map<String, Object> event = new HashMap<>();
      event.put("event", "initialized");
      event.put("duration", exoPlayer.getDuration());

      if (exoPlayer.getVideoFormat() != null) {
        Format videoFormat = exoPlayer.getVideoFormat();
        int width = videoFormat.width;
        int height = videoFormat.height;
        int rotationDegrees = videoFormat.rotationDegrees;
        // Switch the width/height if video was taken in portrait mode
        if (rotationDegrees == 90 || rotationDegrees == 270) {
          width = exoPlayer.getVideoFormat().height;
          height = exoPlayer.getVideoFormat().width;
        }
        event.put("width", width);
        event.put("height", height);
      }
      eventSink.success(event);
    }
  }

  void dispose() {
    if (isInitialized) {
      exoPlayer.stop();
    }
    textureEntry.release();
    eventChannel.setStreamHandler(null);
    if (surface != null) {
      surface.release();
    }
    if (exoPlayer != null) {
      exoPlayer.release();
    }
  }
}
