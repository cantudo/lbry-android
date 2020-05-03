package io.lbry.browser;

import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.ui.PlayerControlView;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.android.material.snackbar.Snackbar;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import io.lbry.browser.adapter.ClaimListAdapter;
import io.lbry.browser.adapter.TagListAdapter;
import io.lbry.browser.dialog.SendTipDialogFragment;
import io.lbry.browser.exceptions.LbryUriException;
import io.lbry.browser.model.Claim;
import io.lbry.browser.model.ClaimCacheKey;
import io.lbry.browser.model.File;
import io.lbry.browser.model.Tag;
import io.lbry.browser.tasks.ClaimSearchTask;
import io.lbry.browser.tasks.FileListTask;
import io.lbry.browser.tasks.LighthouseSearchTask;
import io.lbry.browser.tasks.ResolveTask;
import io.lbry.browser.utils.Helper;
import io.lbry.browser.utils.Lbry;
import io.lbry.browser.utils.LbryUri;

public class FileViewActivity extends AppCompatActivity {

    public static FileViewActivity instance = null;
    private static final int RELATED_CONTENT_SIZE = 16;
    private static final int SHARE_REQUEST_CODE = 3001;
    private static boolean startingShareActivity;

    private SimpleExoPlayer player;
    private boolean hasLoadedFirstBalance;
    private boolean loadFilePending;
    private boolean resolving;
    private Claim claim;
    private ClaimListAdapter relatedContentAdapter;
    private File file;
    private BroadcastReceiver sdkReceiver;
    private Player.EventListener fileViewPlayerListener;

    private View buttonShareAction;
    private View buttonTipAction;
    private View buttonRepostAction;
    private View buttonDownloadAction;
    private View buttonEditAction;
    private View buttonDeleteAction;
    private View buttonReportAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String claimId = null;
        String url = null;
        Intent intent = getIntent();
        if (intent != null) {
            claimId = intent.getStringExtra("claimId");
            url = intent.getStringExtra("url");
        }
        if (Helper.isNullOrEmpty(url)) {
            // This activity should not be opened without a url set
            finish();
            return;
        }

        instance = this;
        ClaimCacheKey key = new ClaimCacheKey();
        key.setClaimId(claimId);
        if (url.contains("#")) {
            key.setPermanentUrl(url); // use the same url for the key so that we can match the key for any value that's the same
            key.setCanonicalUrl(url);
            key.setShortUrl(url);
        }
        if (Lbry.claimCache.containsKey(key)) {
            claim = Lbry.claimCache.get(key);
            checkAndResetNowPlayingClaim();
            file = claim.getFile();
            if (file == null) {
                loadFile();
            }
        }
        setContentView(R.layout.activity_file_view);

        if (claim == null) {
            resolveUrl(url);
        }

        registerSdkReceiver();

        fileViewPlayerListener = new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            /*if (playbackState == Player.STATE_READY) {
                MainActivity.setNowPlayingClaim(claim, FileViewActivity.this);
            }*/
            }
        };

        initUi();
        onWalletBalanceUpdated();
        renderClaim();
    }

    private void checkAndResetNowPlayingClaim() {
        if (MainActivity.nowPlayingClaim != null &&
                !MainActivity.nowPlayingClaim.getClaimId().equalsIgnoreCase(claim.getClaimId())) {
            MainActivity.clearNowPlayingClaim(this);
        }
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        MainActivity.startingFileViewActivity = false;
        if (intent != null) {
            String newClaimId = intent.getStringExtra("claimId");
            String newUrl = intent.getStringExtra("url");

            String oldClaimId = claim != null ? claim.getClaimId() : null;
            if (!Helper.isNullOrEmpty(newClaimId)) {
                if (newClaimId.equalsIgnoreCase(oldClaimId)) {
                    // it's the same claim, so we do nothing
                    if (MainActivity.appPlayer != null) {
                        PlayerView view = findViewById(R.id.file_view_exoplayer_view);
                        view.setPlayer(null);
                        view.setPlayer(MainActivity.appPlayer);
                    }
                    return;
                }

                ClaimCacheKey key = new ClaimCacheKey();
                key.setClaimId(newClaimId);
                if (!Helper.isNullOrEmpty(newUrl) && newUrl.contains("#")) {
                    key.setPermanentUrl(newUrl);
                    key.setCanonicalUrl(newUrl);
                    key.setShortUrl(newUrl);
                }
                if (Lbry.claimCache.containsKey(key)) {
                    claim = Lbry.claimCache.get(key);
                    checkAndResetNowPlayingClaim();
                    file = claim.getFile();
                    if (file == null) {
                        loadFile();
                    }
                    renderClaim();
                } else {
                    findViewById(R.id.file_view_claim_display_area).setVisibility(View.INVISIBLE);
                    resolveUrl(newUrl);
                }
            }
        }
    }

    private void registerSdkReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(MainActivity.ACTION_SDK_READY);
        filter.addAction(MainActivity.ACTION_WALLET_BALANCE_UPDATED);
        sdkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equalsIgnoreCase(MainActivity.ACTION_SDK_READY)) {
                    // authenticate after we receive the sdk ready event
                    if (loadFilePending) {
                        loadFile();
                    }
                } else if (action.equalsIgnoreCase(MainActivity.ACTION_WALLET_BALANCE_UPDATED)) {
                    onWalletBalanceUpdated();
                }
            }
        };
        registerReceiver(sdkReceiver, filter);
    }

    private void onWalletBalanceUpdated() {
        if (Lbry.SDK_READY) {
            if (!hasLoadedFirstBalance) {
                findViewById(R.id.floating_balance_loading).setVisibility(View.GONE);
                findViewById(R.id.floating_balance_value).setVisibility(View.VISIBLE);
                hasLoadedFirstBalance = true;
            }

            ((TextView) findViewById(R.id.floating_balance_value)).setText(
                    Helper.shortCurrencyFormat(Lbry.walletBalance.getAvailable().doubleValue()));
        }
    }

    private String getStreamingUrl() {
        if (file != null && !Helper.isNullOrEmpty(file.getStreamingUrl())) {
            return file.getStreamingUrl();
        }

        return buildLbryTvStreamingUrl();
    }

    private String buildLbryTvStreamingUrl() {
        return String.format("https://player.lbry.tv/content/claims/%s/%s/stream", claim.getName(), claim.getClaimId());
    }

    private void loadFile() {
        if (!Lbry.SDK_READY) {
            // make use of the lbry.tv streaming URL
            loadFilePending = true;
            return;
        }

        loadFilePending = false;
        // TODO: Check if it's paid content and then wait for the user to explicity request the file
        String claimId = claim.getClaimId();
        FileListTask task = new FileListTask(claimId, null, new FileListTask.FileListResultHandler() {
            @Override
            public void onSuccess(List<File> files) {
                if (files.size() > 0) {
                    file = files.get(0);
                    claim.setFile(file);
                }
            }

            @Override
            public void onError(Exception error) {

            }
        });
    }

    protected void onResume() {
        super.onResume();
        MainActivity.startingFileViewActivity = false;
    }

    private void resolveUrl(String url) {
        resolving = true;
        View loadingView = findViewById(R.id.file_view_loading_container);
        ResolveTask task = new ResolveTask(url, Lbry.LBRY_TV_CONNECTION_STRING, loadingView, new ResolveTask.ResolveResultHandler() {
            @Override
            public void onSuccess(List<Claim> claims) {
                if (claims.size() > 0) {
                    claim = claims.get(0);
                    if (Claim.TYPE_REPOST.equalsIgnoreCase(claim.getValueType())) {
                        claim = claim.getRepostedClaim();

                        // cache the reposted claim too for subsequent loads
                        ClaimCacheKey key = ClaimCacheKey.fromClaim(claim);
                        Lbry.claimCache.put(key, claim);
                    }

                    checkAndResetNowPlayingClaim();
                    loadFile();
                    renderClaim();
                }
            }

            @Override
            public void onError(Exception error) {
                resolving = false;
            }
        });
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void initUi() {
        findViewById(R.id.file_view_title_area).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImageView descIndicator = findViewById(R.id.file_view_desc_toggle_arrow);
                View descriptionArea = findViewById(R.id.file_view_description_area);
                if (descriptionArea.getVisibility() != View.VISIBLE) {
                    descriptionArea.setVisibility(View.VISIBLE);
                    descIndicator.setImageResource(R.drawable.ic_arrow_dropup);
                } else {
                    descriptionArea.setVisibility(View.GONE);
                    descIndicator.setImageResource(R.drawable.ic_arrow_dropdown);
                }
            }
        });

        findViewById(R.id.file_view_action_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (claim != null) {
                    try {
                        String shareUrl = LbryUri.parse(
                                !Helper.isNullOrEmpty(claim.getShortUrl()) ? claim.getShortUrl() : claim.getPermanentUrl()).toTvString();
                        Intent shareIntent = new Intent();
                        shareIntent.setAction(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, shareUrl);

                        startingShareActivity = true;
                        Intent shareUrlIntent = Intent.createChooser(shareIntent, getString(R.string.share_lbry_content));
                        shareUrlIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(shareUrlIntent);
                    } catch (LbryUriException ex) {
                        // pass
                    }
                }
            }
        });

        findViewById(R.id.file_view_action_tip).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!Lbry.SDK_READY) {
                    Snackbar.make(findViewById(R.id.file_view_claim_display_area), R.string.sdk_initializing_functionality, Snackbar.LENGTH_LONG).show();
                    return;
                }

                if (claim != null) {
                    SendTipDialogFragment dialog = SendTipDialogFragment.newInstance();
                    dialog.setClaim(claim);
                    dialog.setListener(new SendTipDialogFragment.SendTipListener() {
                        @Override
                        public void onTipSent(BigDecimal amount) {
                            double sentAmount = amount.doubleValue();
                            String message = getResources().getQuantityString(
                                    R.plurals.you_sent_a_tip, sentAmount == 1.0 ? 1 : 2,
                                    new DecimalFormat("#,###.##").format(sentAmount));
                            Snackbar.make(findViewById(R.id.file_view_claim_display_area), message, Snackbar.LENGTH_LONG).show();
                        }
                    });
                    dialog.show(getSupportFragmentManager(), SendTipDialogFragment.TAG);
                }
            }
        });

        RecyclerView relatedContentList = findViewById(R.id.file_view_related_content_list);
        relatedContentList.setNestedScrollingEnabled(false);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        relatedContentList.setLayoutManager(llm);
    }

    private void renderClaim() {
        if (claim == null) {
            return;
        }

        ((NestedScrollView) findViewById(R.id.file_view_scroll_view)).scrollTo(0, 0);
        findViewById(R.id.file_view_claim_display_area).setVisibility(View.VISIBLE);

        ImageView descIndicator = findViewById(R.id.file_view_desc_toggle_arrow);
        descIndicator.setImageResource(R.drawable.ic_arrow_dropdown);

        findViewById(R.id.file_view_description_area).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.file_view_title)).setText(claim.getTitle());
        ((TextView) findViewById(R.id.file_view_description)).setText(claim.getDescription());
        ((TextView) findViewById(R.id.file_view_publisher_name)).setText(
                Helper.isNullOrEmpty(claim.getPublisherName()) ? getString(R.string.anonymous) : claim.getPublisherName());

        RecyclerView descTagsList = findViewById(R.id.file_view_tag_list);
        FlexboxLayoutManager flm = new FlexboxLayoutManager(this);
        descTagsList.setLayoutManager(flm);

        List<Tag> tags = claim.getTagObjects();
        TagListAdapter tagListAdapter = new TagListAdapter(tags, this);
        tagListAdapter.setClickListener(new TagListAdapter.TagClickListener() {
            @Override
            public void onTagClicked(Tag tag, int customizeMode) {
                if (customizeMode == TagListAdapter.CUSTOMIZE_MODE_NONE) {
                    Intent intent = new Intent(MainActivity.ACTION_OPEN_ALL_CONTENT_TAG);
                    intent.putExtra("tag", tag.getName());
                    sendBroadcast(intent);
                    moveTaskToBack(true);
                }
            }
        });
        descTagsList.setAdapter(tagListAdapter);
        findViewById(R.id.file_view_tag_area).setVisibility(tags.size() > 0 ? View.VISIBLE : View.GONE);

        Claim.GenericMetadata metadata = claim.getValue();
        if (metadata instanceof Claim.StreamMetadata) {
            Claim.StreamMetadata streamMetadata = (Claim.StreamMetadata) metadata;
            long publishTime = streamMetadata.getReleaseTime() > 0 ? streamMetadata.getReleaseTime() * 1000 : claim.getTimestamp() * 1000;
            ((TextView) findViewById(R.id.file_view_publish_time)).setText(DateUtils.getRelativeTimeSpanString(
                    publishTime, System.currentTimeMillis(), 0, DateUtils.FORMAT_ABBREV_RELATIVE));

            // Check the metadata type
            String mediaType = streamMetadata.getSource().getMediaType();
            // Use Exoplayer view if it's video / audio
            if (mediaType.startsWith("audio") || mediaType.startsWith("video")) {
                showExoplayerView();
                playMedia();
            } else if (mediaType.startsWith("text")) {

            } else if (mediaType.startsWith("image")) {

            } else {
                // unsupported type
                showUnsupportedView();
            }
        }

        loadRelatedContent();
    }

    private void showUnsupportedView() {
        findViewById(R.id.file_view_exoplayer_container).setVisibility(View.GONE);

        findViewById(R.id.file_view_unsupported_container).setVisibility(View.VISIBLE);
    }

    private void showExoplayerView() {
        findViewById(R.id.file_view_unsupported_container).setVisibility(View.GONE);

        findViewById(R.id.file_view_exoplayer_container).setVisibility(View.VISIBLE);
    }

    private void playMedia() {
        boolean newPlayerCreated = false;
        if (MainActivity.appPlayer == null) {
            MainActivity.appPlayer = new SimpleExoPlayer.Builder(this).build();
            MainActivity.appPlayer.setPlayWhenReady(true);
            MainActivity.appPlayer.addListener(fileViewPlayerListener);

            newPlayerCreated = true;
        }

        PlayerView view = findViewById(R.id.file_view_exoplayer_view);
        PlayerControlView controlView = findViewById(R.id.file_view_exoplayer_control_view);
        view.setPlayer(MainActivity.appPlayer);
        controlView.setPlayer(MainActivity.appPlayer);

        if (MainActivity.nowPlayingClaim != null &&
                MainActivity.nowPlayingClaim.getClaimId().equalsIgnoreCase(claim.getClaimId()) &&
                !newPlayerCreated) {
            // if the claim is already playing, we don't need to reload the media source
            return;
        }

        MainActivity.setNowPlayingClaim(claim, FileViewActivity.this);
        String userAgent = Util.getUserAgent(this, getString(R.string.app_name));
        MediaSource mediaSource = new ProgressiveMediaSource.Factory(
                new DefaultDataSourceFactory(this, userAgent),
                new DefaultExtractorsFactory()
        ).createMediaSource(Uri.parse(getStreamingUrl()));
        MainActivity.appPlayer.prepare(mediaSource, true, true);
    }

    private void loadViewCount() {

    }

    private void loadRelatedContent() {
        // reset the list view
        ((RecyclerView) findViewById(R.id.file_view_related_content_list)).setAdapter(null);

        String title = claim.getTitle();
        String claimId = claim.getClaimId();
        ProgressBar relatedLoading = findViewById(R.id.file_view_related_content_progress);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        boolean canShowMatureContent = sp.getBoolean(MainActivity.PREFERENCE_KEY_SHOW_MATURE_CONTENT, false);
        LighthouseSearchTask relatedTask = new LighthouseSearchTask(
                title, RELATED_CONTENT_SIZE, 0, canShowMatureContent, claimId, relatedLoading, new ClaimSearchTask.ClaimSearchResultHandler() {
            @Override
            public void onSuccess(List<Claim> claims, boolean hasReachedEnd) {
                List<Claim> filteredClaims = new ArrayList<>();
                for (Claim c : claims) {
                    if (!c.getClaimId().equalsIgnoreCase(claim.getClaimId())) {
                        filteredClaims.add(c);
                    }
                }

                relatedContentAdapter = new ClaimListAdapter(filteredClaims, FileViewActivity.this);
                relatedContentAdapter.setListener(new ClaimListAdapter.ClaimListItemListener() {
                    @Override
                    public void onClaimClicked(Claim claim) {
                        Intent intent = new Intent(FileViewActivity.this, FileViewActivity.class);
                        intent.putExtra("claimId", claim.getClaimId());
                        intent.putExtra("url", claim.getPermanentUrl());
                        MainActivity.startingFileViewActivity = true;
                        startActivity(intent);
                    }
                });

                RecyclerView relatedContentList = findViewById(R.id.file_view_related_content_list);
                relatedContentList.setAdapter(relatedContentAdapter);
                relatedContentAdapter.notifyDataSetChanged();

                Helper.setViewVisibility(findViewById(R.id.file_view_no_related_content), relatedContentAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(Exception error) {

            }
        });
        relatedTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void onBackPressed() {
        MainActivity.mainActive = true;
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    protected void onUserLeaveHint() {
        if (startingShareActivity) {
            // share activity triggered this, so reset the flag at this point
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    startingShareActivity = false;
                }
            }, 1000);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !MainActivity.mainActive) {
            PictureInPictureParams params = new PictureInPictureParams.Builder().build();
            enterPictureInPictureMode(params);
        }
    }

    protected void onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInPictureInPictureMode() && MainActivity.appPlayer != null) {
                MainActivity.appPlayer.setPlayWhenReady(false);
            }
        }
        super.onStop();
    }

    protected void onDestroy() {
        Helper.unregisterReceiver(sdkReceiver, this);
        if (MainActivity.appPlayer != null && fileViewPlayerListener != null) {
            MainActivity.appPlayer.removeListener(fileViewPlayerListener);
        }
        instance = null;
        super.onDestroy();
    }

    private void renderPictureInPictureMode() {
        findViewById(R.id.file_view_scroll_view).setVisibility(View.GONE);
        findViewById(R.id.file_view_exoplayer_control_view).setVisibility(View.GONE);
    }
    private void renderFullMode() {
        findViewById(R.id.file_view_scroll_view).setVisibility(View.VISIBLE);

        PlayerControlView controlView = findViewById(R.id.file_view_exoplayer_control_view);
        controlView.setPlayer(null);
        controlView.setPlayer(MainActivity.appPlayer);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        if (isInPictureInPictureMode) {
            renderPictureInPictureMode();
        } else {
            renderFullMode();
        }
    }
}
