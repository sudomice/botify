package net.robinfriedli.botify.audio.youtube;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.similarity.LevenshteinDistance;

import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Playlist;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.PlaylistItemListResponse;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoContentDetails;
import com.google.api.services.youtube.model.VideoListResponse;
import com.google.api.services.youtube.model.VideoStatistics;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.wrapper.spotify.model_objects.specification.ArtistSimplified;
import com.wrapper.spotify.model_objects.specification.Track;
import net.robinfriedli.botify.Botify;
import net.robinfriedli.botify.audio.AudioTrackLoader;
import net.robinfriedli.botify.audio.PlayableFactory;
import net.robinfriedli.botify.command.commands.PlayCommand;
import net.robinfriedli.botify.command.commands.QueueCommand;
import net.robinfriedli.botify.entities.CurrentYouTubeQuotaUsage;
import net.robinfriedli.botify.exceptions.CommandRuntimeException;
import net.robinfriedli.botify.exceptions.NoResultsFoundException;
import net.robinfriedli.botify.exceptions.UnavailableResourceException;
import net.robinfriedli.botify.exceptions.handlers.LoggingExceptionHandler;
import net.robinfriedli.botify.util.StaticSessionProvider;
import net.robinfriedli.stringlist.StringList;
import org.hibernate.Session;

/**
 * Service to query data from YouTube via the YouTube Data API using the API key defined in settings.properties
 */
public class YouTubeService {

    private static final int REDIRECT_SEARCH_AMOUNT = 5;
    private static final int ARTIST_MATCH_SCORE_MULTIPLIER = 1;
    private static final int VIEW_SCORE_MULTIPLIER = 2;
    private static final int EDIT_DISTANCE_SCORE_MULTIPLIER = 3;
    private static final int INDEX_SCORE_MULTIPLIER = 3;

    private static final int QUOTA_COST_SEARCH = 100;
    private static final int QUOTA_COST_LIST = 1;
    private static final ExecutorService UPDATE_QUOTA_SERVICE = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setUncaughtExceptionHandler(new LoggingExceptionHandler());
        return thread;
    });

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final YouTube youTube;
    private final String apiKey;
    private final int quotaThreshold;
    private final AtomicInteger currentQuota = new AtomicInteger(getPersistentQuota());

    public YouTubeService(YouTube youTube, String apiKey, int hardQuotaLimit) {
        this.youTube = youTube;
        this.apiKey = apiKey;
        double factor = hardQuotaLimit > 50000 ? 0.75 : 0.5;
        quotaThreshold = (int) (hardQuotaLimit * factor);
    }

    public static CurrentYouTubeQuotaUsage getCurrentQuotaUsage(Session session) {
        Class<CurrentYouTubeQuotaUsage> currentYouTubeQuotaUsageClass = CurrentYouTubeQuotaUsage.class;
        return session.createQuery("from " + currentYouTubeQuotaUsageClass.getName(), currentYouTubeQuotaUsageClass).uniqueResult();
    }

    public int getAtomicQuotaUsage() {
        return currentQuota.get();
    }

    public void setAtomicQuotaUsage(int quota) {
        currentQuota.set(quota);
    }

    /**
     * Workaround as Spotify does not allow full playback of tracks via third party APIs using the web api for licencing
     * reasons. Gets the metadata and searches the corresponding YouTube video. The only way to stream from Spotify
     * directly is by using the $preview argument with the {@link PlayCommand} or {@link QueueCommand} which plays the
     * provided mp3 preview.
     * <p>
     * However Spotify MIGHT release an SDK supporting full playback of songs across all devices, not just browsers in
     * which case this method and the corresponding block in {@link PlayableFactory#createPlayable(boolean, Object)} should
     * be removed.
     * For reference, see <a href="https://github.com/spotify/web-api/issues/57">Web playback of Full Tracks - Github</a>
     *
     * @param youTubeVideo the hollow youtube that has already been added to the queue and awaits to receive values
     */
    public void redirectSpotify(HollowYouTubeVideo youTubeVideo) throws IOException {
        Track spotifyTrack = youTubeVideo.getRedirectedSpotifyTrack();

        if (spotifyTrack == null) {
            throw new IllegalArgumentException(youTubeVideo.toString() + " is not a placeholder for a redirected Spotify Track");
        }

        StringList artists = StringList.create(spotifyTrack.getArtists(), ArtistSimplified::getName);
        String searchTerm = spotifyTrack.getName() + " " + artists.toSeparatedString(" ");
        List<String> videoIds;

        if (currentQuota.get() < quotaThreshold) {
            YouTube.Search.List search = youTube.search().list("id,snippet");
            search.setKey(apiKey);
            search.setQ(searchTerm);
            // set topic to filter results to music video
            search.setTopicId("/m/04rlf");
            search.setType("video");
            search.setFields("items(snippet/title,id/videoId)");
            search.setMaxResults(5L);

            List<SearchResult> items = doWithQuota(QUOTA_COST_SEARCH, () -> search.execute().getItems());
            if (items.isEmpty()) {
                youTubeVideo.cancel();
                return;
            }

            videoIds = items.stream().map(item -> item.getId().getVideoId()).collect(Collectors.toList());
        } else {
            AudioTrackLoader audioTrackLoader = new AudioTrackLoader(Botify.get().getAudioManager().getPlayerManager());
            AudioItem audioItem;
            try {
                audioItem = audioTrackLoader.loadByIdentifier("ytsearch:" + searchTerm);
            } catch (FriendlyException e) {
                youTubeVideo.cancel();
                return;
            }

            if (!(audioItem instanceof AudioPlaylist)) {
                youTubeVideo.cancel();
                return;
            }

            AudioPlaylist resultList = (AudioPlaylist) audioItem;
            List<AudioTrack> tracks = resultList.getTracks();

            if (tracks.isEmpty()) {
                youTubeVideo.cancel();
                return;
            }

            List<AudioTrack> audioTracks = tracks.subList(0, Math.min(tracks.size(), REDIRECT_SEARCH_AMOUNT));
            videoIds = audioTracks.stream().map(AudioTrack::getIdentifier).collect(Collectors.toList());
        }

        List<Video> videos = getAllVideos(videoIds);
        if (videos.isEmpty()) {
            youTubeVideo.cancel();
            return;
        }

        Video video = getBestMatch(videos, spotifyTrack, artists);
        String videoId = video.getId();
        long durationMillis = getDurationMillis(videoId);

        String artistString = artists.toSeparatedString(", ");
        String title = spotifyTrack.getName() + " by " + artistString;
        youTubeVideo.setTitle(title);
        youTubeVideo.setId(videoId);
        youTubeVideo.setDuration(durationMillis);
    }

    private long getViewCount(Video video) {
        VideoStatistics statistics = video.getStatistics();
        if (statistics != null) {
            BigInteger viewCount = statistics.getViewCount();
            if (viewCount != null) {
                return viewCount.longValue();
            }
        }

        return 0;
    }

    public YouTubeVideo searchVideo(String searchTerm) throws IOException {
        if (currentQuota.get() < quotaThreshold) {
            List<SearchResult> items = searchVideos(1, searchTerm);
            SearchResult searchResult = items.get(0);
            String videoId = searchResult.getId().getVideoId();
            VideoListResponse videoListResponse = doWithQuota(QUOTA_COST_LIST, () -> youTube.videos().list("snippet,contentDetails")
                .setKey(apiKey)
                .setId(videoId)
                .setFields("items(snippet/title,contentDetails/duration)")
                .setMaxResults(1L)
                .execute());
            Video video = videoListResponse.getItems().get(0);

            return new YouTubeVideoImpl(video.getSnippet().getTitle(), videoId, parseDuration(video));
        } else {
            List<YouTubeVideo> youTubeVideos = searchVideosViaLavaplayer(searchTerm, 1);
            return youTubeVideos.get(0);
        }
    }

    private List<YouTubeVideo> searchVideosViaLavaplayer(String searchTerm, int limit) {
        AudioTrackLoader audioTrackLoader = new AudioTrackLoader(Botify.get().getAudioManager().getPlayerManager());
        Object result = audioTrackLoader.loadByIdentifier("ytsearch:" + searchTerm);

        if (!(result instanceof AudioPlaylist) || ((AudioPlaylist) result).getTracks().isEmpty()) {
            throw new NoResultsFoundException(String.format("No YouTube video found for '%s'", searchTerm));
        }

        List<AudioTrack> tracks = ((AudioPlaylist) result).getTracks();
        List<YouTubeVideo> youTubeVideos = Lists.newArrayList();
        for (int i = 0; i < tracks.size() && i < limit; i++) {
            AudioTrack audioTrack = tracks.get(i);
            AudioTrackInfo info = audioTrack.getInfo();
            YouTubeVideo youTubeVideo = new YouTubeVideoImpl(info.title, audioTrack.getIdentifier(), audioTrack.getDuration());
            youTubeVideo.setCached(audioTrack);
            youTubeVideos.add(youTubeVideo);
        }

        return youTubeVideos;
    }

    public List<YouTubeVideo> searchSeveralVideos(int limit, String searchTerm) throws IOException {
        if (currentQuota.get() < quotaThreshold) {
            List<SearchResult> searchResults = searchVideos(limit, searchTerm);
            List<String> videoIds = searchResults.stream().map(result -> result.getId().getVideoId()).collect(Collectors.toList());
            List<Video> youtubeVideos = getAllVideos(videoIds);
            List<YouTubeVideo> videos = Lists.newArrayList();

            for (Video video : youtubeVideos) {
                String videoId = video.getId();
                String title = video.getSnippet().getTitle();
                long duration = parseDuration(video);

                videos.add(new YouTubeVideoImpl(title, videoId, duration));
            }

            return videos;
        } else {
            return searchVideosViaLavaplayer(searchTerm, limit);
        }
    }

    private List<SearchResult> searchVideos(long limit, String searchTerm) throws IOException {
        YouTube.Search.List search = youTube.search().list("id,snippet");
        search.setQ(searchTerm);
        search.setType("video");
        search.setFields("items(snippet/title,id/videoId)");
        search.setMaxResults(limit);
        search.setKey(apiKey);

        List<SearchResult> items = doWithQuota(QUOTA_COST_SEARCH, () -> search.execute().getItems());
        if (items.isEmpty()) {
            throw new NoResultsFoundException(String.format("No YouTube video found for '%s'", searchTerm));
        }

        return items;
    }

    private List<Video> getAllVideos(List<String> videoIds) throws IOException {
        List<Video> videos = Lists.newArrayList();
        YouTube.Videos.List query = youTube.videos().list("snippet,contentDetails,statistics")
            .setKey(apiKey)
            .setId(String.join(",", videoIds))
            .setFields("items(snippet/title,snippet/channelTitle,id,contentDetails/duration,statistics/viewCount)")
            .setMaxResults(50L);

        String nextPageToken;
        do {
            VideoListResponse response = doWithQuota(QUOTA_COST_LIST, query::execute);
            videos.addAll(response.getItems());
            nextPageToken = response.getNextPageToken();
            query.setPageToken(nextPageToken);
        } while (!Strings.isNullOrEmpty(nextPageToken));

        return videos;
    }

    private Map<String, Long> getAllDurations(List<String> videoIds) throws IOException {
        Map<String, Long> durationMap = new HashMap<>();
        List<List<String>> sequences = Lists.partition(videoIds, 50);
        for (List<String> sequence : sequences) {
            durationMap.putAll(getDurationMillis(sequence));
        }

        return durationMap;
    }

    public YouTubePlaylist searchPlaylist(String searchTerm) throws IOException {
        List<SearchResult> items = searchPlaylists(1, searchTerm);
        SearchResult searchResult = items.get(0);
        String playlistId = searchResult.getId().getPlaylistId();
        String title = searchResult.getSnippet().getTitle();
        String channelTitle = searchResult.getSnippet().getChannelTitle();

        int itemCount = doWithQuota(QUOTA_COST_LIST, () -> youTube
            .playlists()
            .list("contentDetails")
            .setKey(apiKey)
            .setId(playlistId)
            .setFields("items(contentDetails/itemCount)")
            .setMaxResults(1L)
            .execute()
            .getItems()
            .get(0)
            .getContentDetails()
            .getItemCount()
            .intValue());

        // return hollow youtube videos so that the playlist items can be loaded asynchronously
        List<HollowYouTubeVideo> videos = Lists.newArrayListWithCapacity(itemCount);
        for (int i = 0; i < itemCount; i++) {
            videos.add(new HollowYouTubeVideo(this));
        }

        return new YouTubePlaylist(title, playlistId, channelTitle, videos);
    }

    public List<YouTubePlaylist> searchSeveralPlaylists(long limit, String searchTerm) throws IOException {
        List<SearchResult> items = searchPlaylists(limit, searchTerm);
        List<String> playlistIds = items.stream().map(item -> item.getId().getPlaylistId()).collect(Collectors.toList());
        Map<String, Long> playlistItemCounts = getAllPlaylistItemCounts(playlistIds);
        List<YouTubePlaylist> playlists = Lists.newArrayList();

        for (SearchResult item : items) {
            String title = item.getSnippet().getTitle();
            String channelTitle = item.getSnippet().getChannelTitle();
            String playlistId = item.getId().getPlaylistId();
            Long longCount = playlistItemCounts.get(playlistId);
            int itemCount = longCount != null ? longCount.intValue() : 0;
            List<HollowYouTubeVideo> videos = Lists.newArrayListWithCapacity(itemCount);
            for (int i = 0; i < itemCount; i++) {
                videos.add(new HollowYouTubeVideo(this));
            }

            playlists.add(new YouTubePlaylist(title, playlistId, channelTitle, videos));
        }

        return playlists;
    }

    private List<SearchResult> searchPlaylists(long limit, String searchTerm) throws IOException {
        YouTube.Search.List playlistSearch = youTube.search().list("id,snippet");
        playlistSearch.setKey(apiKey);
        playlistSearch.setQ(searchTerm);
        playlistSearch.setType("playlist");
        playlistSearch.setFields("items(id/playlistId,snippet/title,snippet/channelTitle)");
        playlistSearch.setMaxResults(limit);

        List<SearchResult> items = doWithQuota(QUOTA_COST_SEARCH, () -> playlistSearch.execute().getItems());
        if (items.isEmpty()) {
            throw new NoResultsFoundException(String.format("No YouTube playlist found for '%s'", searchTerm));
        }

        return items;
    }

    private Map<String, Long> getAllPlaylistItemCounts(List<String> playlistIds) {
        Map<String, Long> itemCounts = new HashMap<>();
        List<List<String>> sequences = Lists.partition(playlistIds, 50);
        for (List<String> sequence : sequences) {
            List<Playlist> playlists = doWithQuota(QUOTA_COST_LIST, () -> youTube
                .playlists()
                .list("contentDetails")
                .setKey(apiKey)
                .setId(String.join(",", sequence))
                .execute()
                .getItems());
            for (Playlist playlist : playlists) {
                itemCounts.put(playlist.getId(), playlist.getContentDetails().getItemCount());
            }
        }

        return itemCounts;
    }

    /**
     * Load each hollow YouTube video of given playlist. This is quite slow because the YouTube API does not allow
     * requesting more than 50 youtube items at once.
     *
     * @param playlist the playlist to load
     */
    public void populateList(YouTubePlaylist playlist) throws IOException {
        if (currentQuota.get() < quotaThreshold) {
            YouTube.PlaylistItems.List itemSearch = youTube.playlistItems().list("snippet");
            itemSearch.setKey(apiKey);
            itemSearch.setMaxResults(50L);
            itemSearch.setFields("items(snippet/title,snippet/resourceId),nextPageToken");
            itemSearch.setPlaylistId(playlist.getId());

            String nextPageToken;
            List<HollowYouTubeVideo> hollowVideos = playlist.getVideos();
            int index = 0;
            do {
                PlaylistItemListResponse response = doWithQuota(QUOTA_COST_LIST, itemSearch::execute);
                nextPageToken = response.getNextPageToken();
                List<PlaylistItem> items = response.getItems();

                List<HollowYouTubeVideo> currentVideos = Lists.newArrayList();
                for (PlaylistItem item : items) {
                    String videoTitle = item.getSnippet().getTitle();
                    String videoId = item.getSnippet().getResourceId().getVideoId();
                    if (index < hollowVideos.size() - 1) {
                        HollowYouTubeVideo hollowVideo = hollowVideos.get(index);
                        hollowVideo.setTitle(videoTitle);
                        hollowVideo.setId(videoId);
                        currentVideos.add(hollowVideo);
                    }
                    ++index;
                }
                loadDurationsAsync(currentVideos);

                itemSearch.setPageToken(nextPageToken);

                if (Thread.currentThread().isInterrupted()) {
                    playlist.cancelLoading();
                    return;
                }
            } while (!Strings.isNullOrEmpty(nextPageToken));
        } else {
            AudioTrackLoader audioTrackLoader = new AudioTrackLoader(Botify.get().getAudioManager().getPlayerManager());
            AudioItem audioItem = audioTrackLoader.loadByIdentifier(playlist.getUrl());

            if (audioItem instanceof AudioPlaylist) {
                List<HollowYouTubeVideo> videos = playlist.getVideos();
                List<AudioTrack> tracks = ((AudioPlaylist) audioItem).getTracks();

                for (int i = 0; i < videos.size() && i < tracks.size(); i++) {
                    HollowYouTubeVideo video = videos.get(i);
                    AudioTrack track = tracks.get(i);

                    video.setTitle(track.getInfo().title);
                    video.setId(track.getIdentifier());
                    video.setDuration(track.getDuration());
                    video.setCached(track);
                }
            }
        }
        // finally cancel each video that couldn't be loaded e.g. if it's private
        playlist.cancelLoading();
    }

    private void loadDurationsAsync(List<HollowYouTubeVideo> videos) {
        // ids have already been loaded in other thread
        List<String> videoIds = Lists.newArrayList();
        for (HollowYouTubeVideo hollowYouTubeVideo : videos) {
            String id;
            try {
                id = hollowYouTubeVideo.getVideoId();
            } catch (UnavailableResourceException e) {
                continue;
            }
            videoIds.add(id);
        }
        // TrackLoadingExceptionHandler
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = Thread.currentThread().getUncaughtExceptionHandler();
        Thread durationLoadingThread = new Thread(() -> {
            try {
                Map<String, Long> durationMillis = getDurationMillis(videoIds);
                for (HollowYouTubeVideo video : videos) {
                    Long duration;
                    try {
                        duration = durationMillis.get(video.getVideoId());
                    } catch (UnavailableResourceException e) {
                        continue;
                    }
                    video.setDuration(duration != null ? duration : 0);
                }
            } catch (IOException e) {
                throw new RuntimeException("Exception occurred while loading durations", e);
            }
        });
        durationLoadingThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        durationLoadingThread.setName(Thread.currentThread().getName() + " (durations)");
        durationLoadingThread.start();
    }

    /**
     * Load a specific playlist item used when the {@link #populateList(YouTubePlaylist)} has not loaded the requested
     * item yet. This is not very efficient since several requests have to be made to find the appropriate page token
     * but it's necessary if shuffle is enabled when loading a large playlist as the populateList methods might take a
     * while until the items towards the end of the list are loaded.
     *
     * @param index    the index of the item to load
     * @param playlist the playlist the item is a part of
     * @deprecated deprecated as of 1.2.1 since the method is unreliable when the playlist contains unavailable items and
     * very inefficient for minimal gain
     */
    @Deprecated
    public void loadPlaylistItem(int index, YouTubePlaylist playlist) {
        if (index < 0 || index >= playlist.getVideos().size()) {
            throw new IllegalArgumentException("Index " + index + " out of bounds for list " + playlist.getTitle());
        }

        int page = index / 50;
        int currentPage = 0;

        try {
            String pageToken = null;
            if (page > 0) {
                YouTube.PlaylistItems.List tokenSearch = youTube.playlistItems().list("id");
                tokenSearch.setMaxResults(50L);
                tokenSearch.setFields("nextPageToken");
                tokenSearch.setPlaylistId(playlist.getId());
                tokenSearch.setKey(apiKey);
                while (currentPage < page) {
                    pageToken = tokenSearch.execute().getNextPageToken();
                    tokenSearch.setPageToken(pageToken);

                    if (pageToken == null) {
                        //should not happen unless page was calculated incorrectly
                        throw new IllegalStateException("Page token search went out of bounds when searching playlist item. Expected more pages.");
                    }

                    ++currentPage;
                }
            }

            YouTube.PlaylistItems.List itemSearch = youTube.playlistItems().list("snippet");
            itemSearch.setMaxResults(50L);
            itemSearch.setFields("items(snippet/title,snippet/resourceId)");
            itemSearch.setPlaylistId(playlist.getId());
            itemSearch.setKey(apiKey);
            if (pageToken != null) {
                itemSearch.setPageToken(pageToken);
            }
            List<PlaylistItem> playlistItems = itemSearch.execute().getItems();

            // get the index the item has on the current page (value between 0-50)
            // e.g the item at index 123 is the item with index 23 on the third page (page 2)
            int indexOnPage = index - page * 50;
            PlaylistItem playlistItem = playlistItems.get(indexOnPage);
            String videoId = playlistItem.getSnippet().getResourceId().getVideoId();
            String title = playlistItem.getSnippet().getTitle();
            HollowYouTubeVideo hollowYouTubeVideo = playlist.getVideos().get(index);
            hollowYouTubeVideo.setId(videoId);
            hollowYouTubeVideo.setTitle(title);
            hollowYouTubeVideo.setDuration(getDurationMillis(videoId));
        } catch (IOException e) {
            throw new RuntimeException("Exception occurred while loading playlist item " + index + " for list " + playlist.getTitle(), e);
        }
    }

    public YouTubeVideo requireVideoForId(String id) throws IOException {
        YouTubeVideo videoForId = getVideoForId(id);

        if (videoForId == null) {
            throw new NoResultsFoundException(String.format("No YouTube video found for id '%s'", id));
        }

        return videoForId;
    }

    @Nullable
    public YouTubeVideo getVideoForId(String id) throws IOException {
        if (currentQuota.get() < quotaThreshold) {
            YouTube.Videos.List videoRequest = youTube.videos().list("snippet");
            videoRequest.setId(id);
            videoRequest.setFields("items(contentDetails/duration,snippet/title)");
            videoRequest.setKey(apiKey);
            videoRequest.setMaxResults(1L);
            List<Video> items = doWithQuota(QUOTA_COST_LIST, () -> videoRequest.execute().getItems());

            if (items.isEmpty()) {
                return null;
            }

            Video video = items.get(0);
            return new YouTubeVideoImpl(video.getSnippet().getTitle(), id, getDurationMillis(id));
        } else {
            AudioTrackLoader audioTrackLoader = new AudioTrackLoader(Botify.get().getAudioManager().getPlayerManager());
            AudioItem result;
            try {
                result = audioTrackLoader.loadByIdentifier(String.format("https://www.youtube.com/watch?v=%s", id));
            } catch (FriendlyException e) {
                return null;
            }

            if (result instanceof AudioTrack) {
                AudioTrack track = (AudioTrack) result;

                YouTubeVideo youTubeVideo = new YouTubeVideoImpl(track.getInfo().title, track.getIdentifier(), track.getDuration());
                youTubeVideo.setCached(track);
                return youTubeVideo;
            } else {
                return null;
            }
        }
    }

    public YouTubePlaylist playlistForId(String id) throws IOException {
        YouTube.Playlists.List playlistRequest = youTube.playlists().list("snippet,contentDetails");
        playlistRequest.setId(id);
        playlistRequest.setFields("items(contentDetails/itemCount,snippet/title,snippet/channelTitle)");
        playlistRequest.setKey(apiKey);
        List<Playlist> items = doWithQuota(QUOTA_COST_LIST, () -> playlistRequest.execute().getItems());

        if (items.isEmpty()) {
            throw new NoResultsFoundException(String.format("No YouTube playlist found for id '%s'", id));
        }

        Playlist playlist = items.get(0);

        List<HollowYouTubeVideo> videoPlaceholders = Lists.newArrayList();
        for (int i = 0; i < playlist.getContentDetails().getItemCount(); i++) {
            videoPlaceholders.add(new HollowYouTubeVideo(this));
        }

        return new YouTubePlaylist(playlist.getSnippet().getTitle(), id, playlist.getSnippet().getChannelTitle(), videoPlaceholders);
    }

    /**
     * Calls the video source to retrieve its duration in milliseconds
     *
     * @param videoId the id of the video
     * @return the video's duration in milliseconds
     */
    private long getDurationMillis(String videoId) throws IOException {
        YouTube.Videos.List videosRequest = youTube.videos().list("contentDetails");
        videosRequest.setKey(apiKey);
        videosRequest.setId(videoId);
        videosRequest.setFields("items(contentDetails/duration)");
        VideoListResponse videoListResponse = doWithQuota(QUOTA_COST_LIST, videosRequest::execute);
        List<Video> items = videoListResponse.getItems();
        if (items.size() == 1) {
            return parseDuration(items.get(0));
        }

        // video detail might not get found if the video is unavailable
        return 0;
    }

    private Map<String, Long> getDurationMillis(Collection<String> videoIds) throws IOException {
        if (videoIds.size() > 50) {
            throw new IllegalArgumentException("Cannot request more than 50 ids at once");
        }

        YouTube.Videos.List videosRequest = youTube.videos().list("contentDetails");
        videosRequest.setKey(apiKey);
        videosRequest.setId(String.join(",", videoIds));
        videosRequest.setFields("items(contentDetails/duration,id)");
        List<Video> videos = doWithQuota(QUOTA_COST_LIST, () -> videosRequest.execute().getItems());

        Map<String, Long> durationMap = new HashMap<>();
        for (Video video : videos) {
            String id = video.getId();
            durationMap.put(id, parseDuration(video));
        }

        return durationMap;
    }

    private long parseDuration(Video video) {
        VideoContentDetails contentDetails = video.getContentDetails();
        if (contentDetails != null) {
            String duration = contentDetails.getDuration();
            // ChronoUnit.MILLIS not supported because of the accuracy YouTube returns
            return Strings.isNullOrEmpty(duration) ? 0 : Duration.parse(duration).get(ChronoUnit.SECONDS) * 1000;
        }

        return 0;
    }

    private <E> E doWithQuota(int cost, Callable<E> callable) {
        try {
            E retVal = callable.call();
            int prevVal = currentQuota.getAndAdd(cost);
            int newVal = currentQuota.get();
            if (prevVal < quotaThreshold && newVal >= quotaThreshold) {
                logger.warn("Reached the YouTube quota threshold of " + quotaThreshold
                    + ". From now on queries will no longer be executed via the YouTube API but by lavaplayer (except for playlists) " +
                    "until the quota resets at midnight PT.");
            }

            UPDATE_QUOTA_SERVICE.execute(() -> updatePersistentQuota(newVal));
            return retVal;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CommandRuntimeException(e);
        }
    }

    private int getPersistentQuota() {
        // read the value in a fresh session to ensure the current value in the database is returned, not the cached state
        // of the current session
        try (Session session = StaticSessionProvider.getSessionFactory().openSession()) {
            CurrentYouTubeQuotaUsage currentQuotaUsage = getCurrentQuotaUsage(session);
            return currentQuotaUsage != null ? currentQuotaUsage.getQuota() : 0;
        }
    }

    private void updatePersistentQuota(int newValue) {
        StaticSessionProvider.consumeSession(session -> {
            CurrentYouTubeQuotaUsage currentQuotaUsage = getCurrentQuotaUsage(session);
            currentQuotaUsage.setQuota(newValue);
        });
    }

    private Video getBestMatch(List<Video> videos, Track spotifyTrack, StringList artists) {
        Video video;
        int size = videos.size();
        if (size == 1) {
            video = videos.get(0);
        } else {
            Map<Integer, Video> videosByScore = new HashMap<>();
            Map<Video, Integer> editDistanceMap = new HashMap<>();
            long[] viewCounts = new long[size];
            for (int i = 0; i < size; i++) {
                Video v = videos.get(i);
                viewCounts[i] = getViewCount(v);
                editDistanceMap.put(v, getBestEditDistance(spotifyTrack, v));
            }

            int index = 0;
            for (Video v : videos) {
                int artistMatchScore = 0;
                if (artists.stream().anyMatch(a -> {
                    String artist = a.toLowerCase();
                    String artistNoSpace = artist.replace(" ", "");
                    String channel = v.getSnippet().getChannelTitle().toLowerCase();
                    String channelNoSpace = channel.replace(" ", "");
                    return channel.contains(artist) || artist.contains(channel) || channelNoSpace.contains(artistNoSpace) || artistNoSpace.contains(channelNoSpace);
                })) {
                    artistMatchScore = ARTIST_MATCH_SCORE_MULTIPLIER * size;
                }

                long viewCount = getViewCount(v);
                int editDistance = editDistanceMap.get(v);
                long viewRank = Arrays.stream(viewCounts).filter(c -> viewCount < c).count();
                long editDistanceRank = editDistanceMap.values().stream().filter(d -> d < editDistance).count();

                int viewScore = VIEW_SCORE_MULTIPLIER * (int) (size - viewRank);
                int editDistanceScore = EDIT_DISTANCE_SCORE_MULTIPLIER * (int) (size - editDistanceRank);
                int indexScore = INDEX_SCORE_MULTIPLIER * (size - index);
                int totalScore = artistMatchScore + viewScore + editDistanceScore + indexScore;
                videosByScore.putIfAbsent(totalScore, v);
                ++index;
            }

            @SuppressWarnings("OptionalGetWithoutIsPresent")
            int bestScore = videosByScore.keySet().stream().mapToInt(k -> k).max().getAsInt();
            video = videosByScore.get(bestScore);
        }

        return video;
    }

    private int getBestEditDistance(Track spotifyTrack, Video video) {
        LevenshteinDistance levenshteinDistance = LevenshteinDistance.getDefaultInstance();
        String trackName = spotifyTrack.getName().toLowerCase();
        String videoTitle = video.getSnippet().getTitle().toLowerCase();
        ArtistSimplified[] artists = spotifyTrack.getArtists();
        String firstArtist = artists.length > 0 ? artists[0].getName().toLowerCase() : "";
        StringList artistNames = StringList.create(artists, ArtistSimplified::getName);
        String artistString = artistNames
            .toSeparatedString(", ")
            .toLowerCase();
        String featuringArtistString = artistNames.size() > 1
            ? artistNames.subList(1).toSeparatedString(", ").toLowerCase()
            : "";

        int parenthesesNotEscaped = bestEditDistanceForParams(levenshteinDistance, trackName, videoTitle, artists, artistString, firstArtist, featuringArtistString);
        String videoTitleWithParenthesesRemoved = videoTitle.replaceAll("\\(.*\\)", "");
        int parenthesesEscaped = bestEditDistanceForParams(levenshteinDistance, trackName, videoTitleWithParenthesesRemoved, artists, artistString, firstArtist, featuringArtistString);
        return Math.min(parenthesesEscaped, parenthesesNotEscaped);
    }

    private int bestEditDistanceForParams(LevenshteinDistance levenshteinDistance, String trackName, String videoTitle, ArtistSimplified[] artists, String artistString, String firstArtist, String featuringArtistString) {
        int distancePlain = levenshteinDistance.apply(trackName, videoTitle);
        int distanceSingleArtistFront = Arrays.stream(artists)
            .mapToInt(artist -> levenshteinDistance.apply(artist.getName().toLowerCase() + " " + trackName, videoTitle))
            .min()
            .orElse(Integer.MAX_VALUE);
        int distanceSingleArtistBack = Arrays.stream(artists)
            .mapToInt(artist -> levenshteinDistance.apply(trackName + " " + artist.getName().toLowerCase(), videoTitle))
            .min()
            .orElse(Integer.MAX_VALUE);
        int distanceArtistsFront = levenshteinDistance.apply(artistString + " " + trackName, videoTitle);
        int distanceArtistsBack = levenshteinDistance.apply(trackName + " " + artistString, videoTitle);
        String featuringArtistsString = firstArtist + " " + trackName + " " + featuringArtistString;
        int distanceFirstArtistFrontFeaturingArtistsBack = levenshteinDistance.apply(featuringArtistsString, videoTitle);

        return IntStream
            .of(distancePlain, distanceSingleArtistFront, distanceSingleArtistBack, distanceArtistsFront, distanceArtistsBack, distanceFirstArtistFrontFeaturingArtistsBack)
            .min()
            .getAsInt();
    }

}
