package com.ryanheise.just_audio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataOutput;
import com.google.android.exoplayer2.metadata.icy.IcyHeaders;
import com.google.android.exoplayer2.metadata.icy.IcyInfo;
import com.google.android.exoplayer2.source.ClippingMediaSource;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.LoopingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.ShuffleOrder;
import com.google.android.exoplayer2.source.ShuffleOrder.DefaultShuffleOrder;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import io.flutter.Log;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

public class AudioPlayer implements MethodCallHandler, Player.EventListener, MetadataOutput {

	static final String TAG = "AudioPlayer";

	private static Random random = new Random();

	private final Context context;
	private final MethodChannel methodChannel;
	private final EventChannel eventChannel;
	private EventSink eventSink;

	private volatile PlaybackState state;
	private long updateTime;
	private long updatePosition;
	private long bufferedPosition;
	private long duration;
	private Long start;
	private Long end;
	private float volume = 1.0f;
	private float speed = 1.0f;
	private Long seekPos;
	private Result prepareResult;
	private Result seekResult;
	private boolean seekProcessed;
	private boolean buffering;
	private boolean justConnected;
	private Map<String, MediaSource> mediaSources = new HashMap<String, MediaSource>();
	private IcyInfo icyInfo;
	private IcyHeaders icyHeaders;

	private SimpleExoPlayer player;
	private MediaSource mediaSource;
	private Integer currentIndex;
	private Map<LoopingMediaSource, MediaSource> loopingChildren = new HashMap<>();
	private Map<LoopingMediaSource, Integer> loopingCounts = new HashMap<>();
	private final Handler handler = new Handler();
	private final Runnable bufferWatcher = new Runnable() {
		@Override
		public void run() {
			if (player == null) {
				return;
			}

			long newBufferedPosition = player.getBufferedPosition();
			if (newBufferedPosition != bufferedPosition) {
				bufferedPosition = newBufferedPosition;
				broadcastPlaybackEvent();
			}
			if (buffering) {
				handler.postDelayed(this, 200);
			} else if (state == PlaybackState.playing) {
				handler.postDelayed(this, 500);
			} else if (state == PlaybackState.paused) {
				handler.postDelayed(this, 1000);
			} else if (justConnected) {
				handler.postDelayed(this, 1000);
			}
		}
	};

	private final Runnable onDispose;

	public AudioPlayer(final Context applicationContext, final BinaryMessenger messenger,
			final String id, final Runnable onDispose) {
		this.context = applicationContext;
		this.onDispose = onDispose;
		methodChannel = new MethodChannel(messenger, "com.ryanheise.just_audio.methods." + id);
		methodChannel.setMethodCallHandler(this);
		eventChannel = new EventChannel(messenger, "com.ryanheise.just_audio.events." + id);
		eventChannel.setStreamHandler(new EventChannel.StreamHandler() {
			@Override
			public void onListen(final Object arguments, final EventSink eventSink) {
				AudioPlayer.this.eventSink = eventSink;
			}

			@Override
			public void onCancel(final Object arguments) {
				eventSink = null;
			}
		});
		state = PlaybackState.none;
	}

	private void startWatchingBuffer() {
		handler.removeCallbacks(bufferWatcher);
		handler.post(bufferWatcher);
	}

	@Override
	public void onMetadata(Metadata metadata) {
		for (int i = 0; i < metadata.length(); i++) {
			final Metadata.Entry entry = metadata.get(i);
			if (entry instanceof IcyInfo) {
				icyInfo = (IcyInfo) entry;
				broadcastPlaybackEvent();
			}
		}
	}

	@Override
	public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
		for (int i = 0; i < trackGroups.length; i++) {
			TrackGroup trackGroup = trackGroups.get(i);

			for (int j = 0; j < trackGroup.length; j++) {
				Metadata metadata = trackGroup.getFormat(j).metadata;

				if (metadata != null) {
					for (int k = 0; k < metadata.length(); k++) {
						final Metadata.Entry entry = metadata.get(k);
						if (entry instanceof IcyHeaders) {
							icyHeaders = (IcyHeaders) entry;
							broadcastPlaybackEvent();
						}
					}
				}
			}
		}
	}

	@Override
	public void onPositionDiscontinuity(int reason) {
		switch (reason) {
		case Player.DISCONTINUITY_REASON_PERIOD_TRANSITION:
		case Player.DISCONTINUITY_REASON_SEEK:
			onItemMayHaveChanged();
			break;
		}
	}

	@Override
	public void onTimelineChanged(Timeline timeline, int reason) {
		if (reason == Player.TIMELINE_CHANGE_REASON_DYNAMIC) {
			onItemMayHaveChanged();
		}
	}

	private void onItemMayHaveChanged() {
		Integer newIndex = player.getCurrentWindowIndex();
		if (newIndex != currentIndex) {
			currentIndex = newIndex;
		}
		broadcastPlaybackEvent();
	}

	@Override
	public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
		switch (playbackState) {
		case Player.STATE_READY:
			if (prepareResult != null) {
				duration = getDuration();
				justConnected = true;
				transition(PlaybackState.stopped);
				prepareResult.success(duration);
				prepareResult = null;
			}
			if (seekProcessed) {
				completeSeek();
			}
			break;
		case Player.STATE_ENDED:
			if (state != PlaybackState.completed) {
				player.setPlayWhenReady(false);
				transition(PlaybackState.completed);
			}
			break;
		}
		final boolean buffering = playbackState == Player.STATE_BUFFERING;
		// don't notify buffering if (buffering && state == stopped)
		final boolean notifyBuffering = !buffering || state != PlaybackState.stopped;
		if (notifyBuffering && (buffering != this.buffering)) {
			this.buffering = buffering;
			broadcastPlaybackEvent();
			if (buffering) {
				startWatchingBuffer();
			}
		}
	}

	@Override
	public void onPlayerError(ExoPlaybackException error) {
		switch (error.type) {
		case ExoPlaybackException.TYPE_SOURCE:
			Log.e(TAG, "TYPE_SOURCE: " + error.getSourceException().getMessage());
			break;

		case ExoPlaybackException.TYPE_RENDERER:
			Log.e(TAG, "TYPE_RENDERER: " + error.getRendererException().getMessage());
			break;

		case ExoPlaybackException.TYPE_UNEXPECTED:
			Log.e(TAG, "TYPE_UNEXPECTED: " + error.getUnexpectedException().getMessage());
			break;

		default:
			Log.e(TAG, "default: " + error.getUnexpectedException().getMessage());
		}
		sendError(String.valueOf(error.type), error.getMessage());
	}

	@Override
	public void onSeekProcessed() {
		if (seekResult != null) {
			seekProcessed = true;
			if (player.getPlaybackState() == Player.STATE_READY) {
				completeSeek();
			}
		}
	}

	private void completeSeek() {
		seekProcessed = false;
		seekPos = null;
		seekResult.success(null);
		seekResult = null;
	}

	@Override
	public void onMethodCall(final MethodCall call, final Result result) {
		ensurePlayerInitialized();

		final List<?> args = (List<?>) call.arguments;
		try {
			switch (call.method) {
			case "load":
				load(getAudioSource(args.get(0)), result);
				break;
			case "play":
				play();
				result.success(null);
				break;
			case "pause":
				pause();
				result.success(null);
				break;
			case "stop":
				stop(result);
				break;
			case "setVolume":
				setVolume((float) ((double) ((Double) args.get(0))));
				result.success(null);
				break;
			case "setSpeed":
				setSpeed((float) ((double) ((Double) args.get(0))));
				result.success(null);
				break;
			case "setLoopMode":
				setLoopMode((Integer) args.get(0));
				result.success(null);
				break;
			case "setShuffleModeEnabled":
				setShuffleModeEnabled((Boolean) args.get(0));
				result.success(null);
				break;
			case "setAutomaticallyWaitsToMinimizeStalling":
				result.success(null);
				break;
			case "seek":
				Long position = getLong(args.get(0));
				Integer index = (Integer)args.get(1);
				seek(position == null ? C.TIME_UNSET : position, result, index);
				break;
			case "dispose":
				dispose();
				result.success(null);
				break;
			case "concatenating.add":
				concatenating(args.get(0))
						.addMediaSource(getAudioSource(args.get(1)), null, () -> result.success(null));
				break;
			case "concatenating.insert":
				concatenating(args.get(0))
						.addMediaSource((Integer)args.get(1), getAudioSource(args.get(2)), null, () -> result.success(null));
				break;
			case "concatenating.addAll":
				concatenating(args.get(0))
						.addMediaSources(getAudioSources(args.get(1)), null, () -> result.success(null));
				break;
			case "concatenating.insertAll":
				concatenating(args.get(0))
						.addMediaSources((Integer)args.get(1), getAudioSources(args.get(2)), null, () -> result.success(null));
				break;
			case "concatenating.removeAt":
				concatenating(args.get(0))
						.removeMediaSource((Integer)args.get(1), null, () -> result.success(null));
				break;
			case "concatenating.removeRange":
				concatenating(args.get(0))
						.removeMediaSourceRange((Integer)args.get(1), (Integer)args.get(2), null, () -> result.success(null));
				break;
			case "concatenating.move":
				concatenating(args.get(0))
						.moveMediaSource((Integer)args.get(1), (Integer)args.get(2), null, () -> result.success(null));
				break;
			case "concatenating.clear":
				concatenating(args.get(0)).clear(null, () -> result.success(null));
				break;
			default:
				result.notImplemented();
				break;
			}
		} catch (IllegalStateException e) {
			e.printStackTrace();
			result.error("Illegal state: " + e.getMessage(), null, null);
		} catch (Exception e) {
			e.printStackTrace();
			result.error("Error: " + e, null, null);
		}
	}

	// Set the shuffle order for mediaSource, with currentIndex at
	// the first position. Traverse the tree incrementing index at each
	// node.
	private int setShuffleOrder(MediaSource mediaSource, int index) {
		if (mediaSource instanceof ConcatenatingMediaSource) {
			final ConcatenatingMediaSource source = (ConcatenatingMediaSource)mediaSource;
			// Find which child is current
			Integer currentChildIndex = null;
			for (int i = 0; i < source.getSize(); i++) {
				final int indexBefore = index;
				final MediaSource child = source.getMediaSource(i);
				index = setShuffleOrder(child, index);
				// If currentIndex falls within this child, make this child come first.
				if (currentIndex >= indexBefore && currentIndex < index) {
					currentChildIndex = i;
				}
			}
			// Shuffle so that the current child is first in the shuffle order
			source.setShuffleOrder(createShuffleOrder(source.getSize(), currentChildIndex));
		} else if (mediaSource instanceof LoopingMediaSource) {
			final LoopingMediaSource source = (LoopingMediaSource)mediaSource;
			// The ExoPlayer API doesn't provide accessors for these so we have
			// to index them ourselves.
			MediaSource child = loopingChildren.get(source);
			int count = loopingCounts.get(source);
			for (int i = 0; i < count; i++) {
				index = setShuffleOrder(child, index);
			}
		} else {
			// An actual media item takes up one spot in the playlist.
			index++;
		}
		return index;
	}

	private static int[] shuffle(int length, Integer firstIndex) {
		final int[] shuffleOrder = new int[length];
		for (int i = 0; i < length; i++) {
			final int j = random.nextInt(i + 1);
			shuffleOrder[i] = shuffleOrder[j];
			shuffleOrder[j] = i;
		}
		if (firstIndex != null) {
			for (int i = 1; i < length; i++) {
				if (shuffleOrder[i] == firstIndex) {
					final int v = shuffleOrder[0];
					shuffleOrder[0] = shuffleOrder[i];
					shuffleOrder[i] = v;
					break;
				}
			}
		}
		return shuffleOrder;
	}

	// Create a shuffle order optionally fixing the first index.
	private ShuffleOrder createShuffleOrder(int length, Integer firstIndex) {
		int[] shuffleIndices = shuffle(length, firstIndex);
		return new DefaultShuffleOrder(shuffleIndices, random.nextLong());
	}

	private ConcatenatingMediaSource concatenating(final Object index) {
		return (ConcatenatingMediaSource)mediaSources.get((Integer)index);
	}

	private MediaSource getAudioSource(final Object json) {
		Map<?, ?> map = (Map<?, ?>)json;
		String id = (String)map.get("id");
		MediaSource mediaSource = mediaSources.get(id);
		if (mediaSource == null) {
			mediaSource = decodeAudioSource(map);
			mediaSources.put(id, mediaSource);
		}
		return mediaSource;
	}

	private MediaSource decodeAudioSource(final Object json) {
		Map<?, ?> map = (Map<?, ?>)json;
		String id = (String)map.get("id");
		switch ((String)map.get("type")) {
		case "progressive":
			return new ProgressiveMediaSource.Factory(buildDataSourceFactory())
					.setTag(id)
					.createMediaSource(Uri.parse((String)map.get("uri")));
		case "dash":
			return new DashMediaSource.Factory(buildDataSourceFactory())
					.setTag(id)
					.createMediaSource(Uri.parse((String)map.get("uri")));
		case "hls":
			return new HlsMediaSource.Factory(buildDataSourceFactory())
					.setTag(id)
					.createMediaSource(Uri.parse((String)map.get("uri")));
		case "concatenating":
			List<Object> audioSources = (List<Object>)map.get("audioSources");
			return new ConcatenatingMediaSource(
					false, // isAtomic
					(Boolean)map.get("useLazyPreparation"),
					new DefaultShuffleOrder(audioSources.size()),
							audioSources
									.stream()
									.map(s -> getAudioSource(s))
									.toArray(MediaSource[]::new));
		case "clipping":
			Long start = getLong(map.get("start"));
			Long end = getLong(map.get("end"));
			return new ClippingMediaSource(getAudioSource(map.get("audioSource")),
					(start != null ? start : 0) * 1000L,
					(end != null ? end : C.TIME_END_OF_SOURCE) * 1000L);
		case "looping":
			Integer count = (Integer)map.get("count");
			MediaSource looperChild = getAudioSource(map.get("audioSource"));
			LoopingMediaSource looper = new LoopingMediaSource(looperChild, count);
			// TODO: store both in a single map
			loopingChildren.put(looper, looperChild);
			loopingCounts.put(looper, count);
			return looper;
		default:
			throw new IllegalArgumentException("Unknown AudioSource type: " + map.get("type"));
		}
	}

	private List<MediaSource> getAudioSources(final Object json) {
		return ((List<Object>)json)
				.stream()
				.map(s -> getAudioSource(s))
				.collect(Collectors.toList());
	}

	private DataSource.Factory buildDataSourceFactory() {
		String userAgent = Util.getUserAgent(context, "just_audio");
		DataSource.Factory httpDataSourceFactory = new DefaultHttpDataSourceFactory(
				userAgent,
				DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
				DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
				true
		);
		return new DefaultDataSourceFactory(context, httpDataSourceFactory);
	}

	private void load(final MediaSource mediaSource, final Result result) {
		justConnected = false;
		switch (state) {
		case none:
			break;
		case connecting:
			abortExistingConnection();
			player.stop();
			player.setPlayWhenReady(false);
			break;
		default:
			player.stop();
			player.setPlayWhenReady(false);
			break;
		}
		prepareResult = result;
		transition(PlaybackState.connecting);
		if (player.getShuffleModeEnabled()) {
			setShuffleOrder(mediaSource, 0);
		}
		this.mediaSource = mediaSource;
		player.prepare(mediaSource);
	}

	private void ensurePlayerInitialized() {
		if (player == null) {
			player = new SimpleExoPlayer.Builder(context).build();
			player.addMetadataOutput(this);
			player.addListener(this);
		}
	}

	private void broadcastPlaybackEvent() {
		final Map<String, Object> event = new HashMap<String, Object>();
		event.put("state", state.ordinal());
		event.put("buffering", buffering);
		event.put("updatePosition", updatePosition = getCurrentPosition());
		event.put("updateTime", updateTime = System.currentTimeMillis());
		event.put("bufferedPosition", Math.max(updatePosition, bufferedPosition));
		event.put("icyMetadata", collectIcyMetadata());
		event.put("duration", duration = getDuration());
		event.put("currentIndex", currentIndex);

		if (eventSink != null) {
			eventSink.success(event);
		}
	}

	private Map<String, Object> collectIcyMetadata() {
		final Map<String, Object> icyData = new HashMap<>();
		if (icyInfo != null) {
			final Map<String, String> info = new HashMap<>();
			info.put("title", icyInfo.title);
			info.put("url", icyInfo.url);
			icyData.put("info", info);
		}
		if (icyHeaders != null) {
			final Map<String, Object> headers = new HashMap<>();
			headers.put("bitrate", icyHeaders.bitrate);
			headers.put("genre", icyHeaders.genre);
			headers.put("name", icyHeaders.name);
			headers.put("metadataInterval", icyHeaders.metadataInterval);
			headers.put("url", icyHeaders.url);
			headers.put("isPublic", icyHeaders.isPublic);
			icyData.put("headers", headers);
		}
		return icyData;
	}

	private long getCurrentPosition() {
		if (state == PlaybackState.none || state == PlaybackState.connecting) {
			return 0;
		} else if (seekPos != null && seekPos != C.TIME_UNSET) {
			return seekPos;
		} else {
			return player.getCurrentPosition();
		}
	}

	private long getDuration() {
		if (state == PlaybackState.none || state == PlaybackState.connecting) {
			return C.TIME_UNSET;
		} else {
			return player.getDuration();
		}
	}

	private void sendError(String errorCode, String errorMsg) {
		if (prepareResult != null) {
			prepareResult.error(errorCode, errorMsg, null);
			prepareResult = null;
		}

		if (eventSink != null) {
			eventSink.error(errorCode, errorMsg, null);
		}
	}

	private void transition(final PlaybackState newState) {
		final PlaybackState oldState = state;
		state = newState;
		broadcastPlaybackEvent();
	}

	private String getLowerCaseExtension(Uri uri) {
		// Until ExoPlayer provides automatic detection of media source types, we
		// rely on the file extension. When this is absent, as a temporary
		// workaround we allow the app to supply a fake extension in the URL
		// fragment. e.g.  https://somewhere.com/somestream?x=etc#.m3u8
		String fragment = uri.getFragment();
		String filename = fragment != null && fragment.contains(".") ? fragment : uri.getPath();
		return filename.replaceAll("^.*\\.", "").toLowerCase();
	}

	public void play() {
		switch (state) {
		case playing:
			break;
		case stopped:
		case completed:
		case paused:
			justConnected = false;
			transition(PlaybackState.playing);
			startWatchingBuffer();
			player.setPlayWhenReady(true);
			break;
		default:
			throw new IllegalStateException(
					"Cannot call play from connecting/none states (" + state + ")");
		}
	}

	public void pause() {
		switch (state) {
		case paused:
			break;
		case playing:
			player.setPlayWhenReady(false);
			transition(PlaybackState.paused);
			break;
		default:
			throw new IllegalStateException(
					"Can call pause only from playing and buffering states (" + state + ")");
		}
	}

	public void stop(final Result result) {
		switch (state) {
		case stopped:
			result.success(null);
			break;
		case connecting:
			abortExistingConnection();
			buffering = false;
			transition(PlaybackState.stopped);
			result.success(null);
			break;
		case completed:
		case playing:
		case paused:
			abortSeek();
			player.setPlayWhenReady(false);
			transition(PlaybackState.stopped);
			player.seekTo(0L);
			result.success(null);
			break;
		default:
			throw new IllegalStateException("Cannot call stop from none state");
		}
	}

	public void setVolume(final float volume) {
		this.volume = volume;
		player.setVolume(volume);
	}

	public void setSpeed(final float speed) {
		this.speed = speed;
		player.setPlaybackParameters(new PlaybackParameters(speed));
		broadcastPlaybackEvent();
	}

	public void setLoopMode(final int mode) {
		player.setRepeatMode(mode);
	}

	public void setShuffleModeEnabled(final boolean enabled) {
		if (enabled) {
			setShuffleOrder(mediaSource, 0);
		}
		player.setShuffleModeEnabled(enabled);
	}

	public void seek(final long position, final Result result, final Integer index) {
		if (state == PlaybackState.none || state == PlaybackState.connecting) {
			throw new IllegalStateException("Cannot call seek from none none/connecting states");
		}
		abortSeek();
		seekPos = position;
		seekResult = result;
		seekProcessed = false;
		int windowIndex = index != null ? index : player.getCurrentWindowIndex();
		player.seekTo(windowIndex, position);
	}

	public void dispose() {
		mediaSources.clear();
		mediaSource = null;
		loopingChildren.clear();
		if (player != null) {
			player.release();
			player = null;
			buffering = false;
			transition(PlaybackState.none);
		}
		onDispose.run();
	}

	private void abortSeek() {
		if (seekResult != null) {
			seekResult.success(null);
			seekResult = null;
			seekPos = null;
			seekProcessed = false;
		}
	}

	private void abortExistingConnection() {
		sendError("abort", "Connection aborted");
	}

	public static Long getLong(Object o) {
		return (o == null || o instanceof Long) ? (Long)o : new Long(((Integer)o).intValue());
	}

	enum PlaybackState {
		none,
		stopped,
		paused,
		playing,
		connecting,
		completed
	}
}