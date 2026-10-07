package com.demo.opencv.socialMedia;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.demo.opencv.UserDataManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.User;
import twitter4j.auth.AccessToken;
import twitter4j.auth.RequestToken;

public class TwitterAuthenticator { // used to obtain user's Twitter access token and secret
    private static final String CONSUMER_KEY = "LMBdIxtET2HNzYRdiXQcqMWqd";
    private static final String CONSUMER_SECRET = "6n68iFVBaZO6JAflA2uTIBF9m17VgeWlw2Bu3U4xZQqJvMysm9";
    private static final String CALLBACK_URL = "gazelink://oauth-callback";
    private final Twitter twitter;
    private final Context context;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public TwitterAuthenticator(Context context) {
        this.context = context;
        twitter = new TwitterFactory().getInstance();
        twitter.setOAuthConsumer(CONSUMER_KEY, CONSUMER_SECRET);
    }

    public void startAuthentication(Context applicationContext) {
        if (!(applicationContext instanceof UserDataManager)) return;
        UserDataManager userDataManager = (UserDataManager) applicationContext;
        Log.d("SocialMediaPost", "Starting Twitter Authentication");
        new Thread(() -> {
            try {
                // storing in shared preferences
                RequestToken requestToken = twitter.getOAuthRequestToken(CALLBACK_URL);
                if (requestToken != null) {
                    userDataManager.setRequestToken(requestToken.getToken());
                    userDataManager.setRequestTokenSecret(requestToken.getTokenSecret());

                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(requestToken.getAuthenticationURL()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
            } catch (Exception e) {
                Log.e("SocialMediaPost", "Twitter OAuth request failed", e);
            }
        }).start();
    }

    public void handleCallback(Uri uri, Context applicationContext) {
        if (uri != null && (uri.toString().startsWith(CALLBACK_URL) || uri.toString().startsWith("vakyam://oauth-callback"))) { // if the intent is correct
            String verifier = uri.getQueryParameter("oauth_verifier");
            if (verifier == null) return;
            if (!(applicationContext instanceof UserDataManager)) return;

            UserDataManager userDataManager = (UserDataManager) applicationContext;
            String requestTokenString = userDataManager.getRequestToken();
            String requestTokenSecretString = userDataManager.getRequestTokenSecret();
            if (requestTokenString == null || requestTokenSecretString == null) return;
            Log.d("SocialMediaPost", "Callback Request Token: " + requestTokenString);
            Log.d("SocialMediaPost", "Callback Request Token Secret: " + requestTokenSecretString);

            RequestToken requestToken = new RequestToken(requestTokenString, requestTokenSecretString);

            executorService.submit(() -> {
                AccessToken accessToken;
                try {
                    accessToken = twitter.getOAuthAccessToken(requestToken, verifier);
                    userDataManager.setAccessToken(accessToken.getToken());
                    userDataManager.setAccessTokenSecret(accessToken.getTokenSecret());
                    Log.d("SocialMediaPost", "Authentication Access token obtained: " + accessToken);
                } catch (Exception e) {
                    Log.e("SocialMediaPost", "Authentication token access failed: " + e.getMessage());
                }
            });
        }
    }
}
