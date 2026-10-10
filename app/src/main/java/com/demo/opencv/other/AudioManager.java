package com.demo.opencv.other;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AudioManager {
    MediaPlayer mediaPlayer = new MediaPlayer();
    private SpeechRecognizer speechRecognizer;
    private final OfflineSpeechRecognizer offlineSpeechRecognizer = new OfflineSpeechRecognizer(); // on-device fallback
    private Context appContext;
    private boolean offlineListening = false;
    TextToSpeech textToSpeech;
    MediaRecorder recorder;
    private String fileName;
    String finalText;
    private volatile boolean isTtsReady = false;
    private final List<String[]> pendingUtterances = new ArrayList<>(); // {text, language}; language null = default voice
    private static final Locale HINDI = new Locale("hi", "IN");
    private static final Locale INDIAN_ENGLISH = new Locale("en", "IN");
    private Locale defaultLocale = Locale.US; // voice for speakText(text), set by setLanguage
    // Hinglish utterance id -> its Roman text, re-spoken in Indian English if the Hindi voice fails
    // (an online-only Hindi voice reports itself available, then fails to synthesise without network)
    private final Map<String, String> romanFallback = new ConcurrentHashMap<>();
    private final AtomicInteger utteranceIds = new AtomicInteger();
    private final UtteranceProgressListener romanRetry = new UtteranceProgressListener() {
        @Override public void onStart(String id) {}
        @Override public void onDone(String id) { if (id != null) romanFallback.remove(id); }
        @Override public void onStop(String id, boolean interrupted) { if (id != null) romanFallback.remove(id); }
        @Override public void onError(String id) { onError(id, TextToSpeech.ERROR); }
        @Override public void onError(String id, int errorCode) {
            String roman = id == null ? null : romanFallback.remove(id);
            if (roman == null || textToSpeech == null) return;
            Log.w("AudioManager", "Hindi voice failed (" + errorCode + "), speaking Hinglish in Indian English");
            if (!applyVoice(INDIAN_ENGLISH)) applyVoice(Locale.US);
            textToSpeech.speak(roman, TextToSpeech.QUEUE_ADD, null, null);
        }
    };

    public interface AudioManagerListener {
        void onAudioUpdated(String key, String value);
    }
    AudioManager.AudioManagerListener audioManagerListener;

    public void setAudioManagerListener(AudioManagerListener audioManagerListener) {
        this.audioManagerListener = audioManagerListener;
    }

    public void initialize(Context mContext) {
        if (mContext == null) return;

        appContext = mContext.getApplicationContext();
        textToSpeech = new TextToSpeech(mContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                if (textToSpeech != null) {
                    applyVoice(defaultLocale);
                    textToSpeech.setOnUtteranceProgressListener(romanRetry);
                }
                isTtsReady = true;
                Log.d("AudioManager", "TTS initialized successfully");
                synchronized (pendingUtterances) {
                    for (String[] utterance : pendingUtterances) {
                        if (textToSpeech != null) {
                            speakNow(utterance[0], utterance[1], TextToSpeech.QUEUE_ADD);
                        }
                    }
                    pendingUtterances.clear();
                }
            } else {
                isTtsReady = false;
                Log.d("AudioManager", "TTS initialization error: " + status);
            }
        });

        File cacheDir = mContext.getExternalCacheDir();
        if (cacheDir == null) {
            cacheDir = mContext.getCacheDir();
        }
        if (cacheDir != null) {
            fileName = new File(cacheDir, "contextAudio.3gp").getAbsolutePath();
        }

        setSpeechRecognizer(mContext); // set speech to text

        offlineSpeechRecognizer.init(mContext); // cheap: the model is only loaded on first offline use
        offlineSpeechRecognizer.setListener(new OfflineSpeechRecognizer.Listener() {
            @Override
            public void onResult(String text) {
                offlineListening = false;
                finalText = text;
                if (audioManagerListener != null) {
                    audioManagerListener.onAudioUpdated("Context", finalText);
                }
            }

            @Override
            public void onError(String error) {
                offlineListening = false;
                if (audioManagerListener != null) {
                    audioManagerListener.onAudioUpdated("Error", error);
                }
            }
        });
    }

    public void setLanguage(String language) {
        defaultLocale = "Hinglish".equals(language) ? HINDI : Locale.US;
        if (textToSpeech != null && isTtsReady) {
            applyVoice(defaultLocale);
        }
    }

    // Indian English recognition copes with Indian accents and writes Hindi speech in English letters,
    // so the caregiver's words stay Roman-script for the keyboard and the LLM in both languages
    private final String speechLang = "en-IN";

    /** Speaks in the voice chosen by {@link #setLanguage}; calibration prompts and caregiver messages use this. */
    public void speakText(String text) {
        speakText(text, null);
    }

    /**
     * Speaks in the voice for [language]: "Hinglish" text (Roman letters) is read by the Hindi voice so the
     * Hindi words sound Hindi; "English" uses US English.
     */
    public void speakText(String text, String language) {
        if (text == null || text.trim().isEmpty()) return;
        Log.d("AudioManager", "Speaking (" + language + "): " + text);
        if (isTtsReady && textToSpeech != null) {
            speakNow(text, language, TextToSpeech.QUEUE_FLUSH);
        } else {
            synchronized (pendingUtterances) {
                pendingUtterances.add(new String[]{text, language});
            }
        }
    }

    private void speakNow(String text, String language, int queueMode) {
        String id = "vakyam-" + utteranceIds.incrementAndGet();
        Locale voice = language == null ? defaultLocale : "Hinglish".equals(language) ? HINDI : Locale.US;
        if (HINDI.equals(voice)) {
            if (applyVoice(HINDI)) {
                romanFallback.put(id, text);
            } else if (!applyVoice(INDIAN_ENGLISH)) {
                applyVoice(Locale.US);
            }
        } else {
            applyVoice(voice);
        }
        textToSpeech.speak(text, queueMode, null, id);
    }

    /** False when the engine has no voice for [locale]; the previous voice is then left in place. */
    private boolean applyVoice(Locale locale) {
        if (textToSpeech == null) return false;
        int result = textToSpeech.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("AudioManager", "No TTS voice for " + locale + " (" + result + ")");
            return false;
        }
        if (HINDI.equals(locale)) { // English keeps the engine's own voice choice
            Voice offline = installedVoice(locale);
            if (offline != null && !offline.equals(textToSpeech.getVoice())) textToSpeech.setVoice(offline);
        }
        return true;
    }

    /** setLanguage can pick a network voice even when an installed one exists; this finds the installed one. */
    private Voice installedVoice(Locale locale) {
        Set<Voice> voices;
        try {
            voices = textToSpeech.getVoices();
        } catch (RuntimeException e) { // some engines throw instead of returning null
            return null;
        }
        if (voices == null) return null;
        Voice best = null;
        for (Voice v : voices) {
            Locale l = v.getLocale();
            if (l == null || !locale.getLanguage().equals(l.getLanguage()) || !locale.getCountry().equals(l.getCountry())) continue;
            if (v.isNetworkConnectionRequired() || v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            if (best == null || v.getQuality() > best.getQuality()) best = v;
        }
        return best;
    }

    public boolean isTtsReady() {
        return isTtsReady;
    }

    public void playAudio(File audio) {
        if (audio == null || !audio.exists()) return;
        try {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
            } else {
                mediaPlayer.reset();
            }
            mediaPlayer.setDataSource(audio.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            Log.e("AudioManager", "playAudio failed", e);
        }
    }

    public void startRecording() {
        if (fileName == null) return;
        try {
            if (recorder != null) {
                recorder.release();
            }
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setOutputFile(fileName);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            Log.e("AudioManager", "prepare() or start() failed", e);
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
        }
    }

    public void stopRecording() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception e) {
                Log.e("AudioManager", "recorder stop failed", e);
            } finally {
                recorder.release();
                recorder = null;
            }
        }
    }

    public void startListening() {
        // Online keeps the system recognizer; offline (or a phone with no system recognizer) uses the on-device model
        boolean useOffline = offlineSpeechRecognizer.isAvailable()
                && (speechRecognizer == null || !NetworkUtils.isOnline(appContext));
        if (useOffline) {
            startOfflineListening();
            return;
        }
        if (speechRecognizer == null) return;
        Log.d("AudioManager", "Online mode detected: Using standard Android SpeechRecognizer");
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang);
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            Log.e("AudioManager", "startListening error", e);
        }
    }

    private void startOfflineListening() {
        Log.d("AudioManager", "Routing to on-device ASR");
        offlineListening = true;
        offlineSpeechRecognizer.setLanguage(speechLang);
        offlineSpeechRecognizer.startListening();
    }

    // The system recognizer could not reach its service or has no language pack, as opposed to simply hearing nothing
    private static boolean isServiceFailure(int error) {
        return error == SpeechRecognizer.ERROR_NETWORK
                || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER
                || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
    }

    public void stopListening() {
        if (offlineListening) {
            offlineSpeechRecognizer.stopListening(); // the captured audio is still transcribed
            return;
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception e) {
                Log.e("AudioManager", "stopListening error", e);
            }
        }
    }

    public void setSpeechRecognizer(Context context) {
        if (context == null) return;
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) { }

                    @Override
                    public void onBeginningOfSpeech() { }

                    @Override
                    public void onRmsChanged(float rmsdB) { }

                    @Override
                    public void onBufferReceived(byte[] buffer) { }

                    @Override
                    public void onEndOfSpeech() { }

                    @Override
                    public void onError(int error) {
                        Log.e("AudioManager", "Speech recognition error: " + error);
                        if (isServiceFailure(error) && offlineSpeechRecognizer.isAvailable()) {
                            speechRecognizer.cancel(); // make sure the microphone is free before recording locally
                            startOfflineListening();
                            return;
                        }
                        if (audioManagerListener != null) {
                            audioManagerListener.onAudioUpdated("Error", String.valueOf(error));
                        }
                    }

                    @Override
                    public void onResults(Bundle results) {
                        if (results == null) return;
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            finalText = matches.get(0).toLowerCase();
                            if (audioManagerListener != null) {
                                audioManagerListener.onAudioUpdated("Context", finalText);
                            }
                            Log.d("AudioManager", "Text recognized: " + finalText);
                        } else {
                            Log.d("AudioManager", "No recognized text");
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) { }

                    @Override
                    public void onEvent(int eventType, Bundle params) { }
                });
            }
        } catch (Exception e) {
            Log.e("AudioManager", "Failed to initialize SpeechRecognizer", e);
        }
    }

    public void destroy() {
        try {
            if (textToSpeech != null) {
                textToSpeech.stop();
                textToSpeech.shutdown();
                textToSpeech = null;
                isTtsReady = false;
            }
        } catch (Exception e) {
            Log.e("AudioManager", "Error shutting down TTS", e);
        }
        try {
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
        } catch (Exception e) {
            Log.e("AudioManager", "Error destroying speech recognizer", e);
        }
        offlineSpeechRecognizer.destroy();
        offlineListening = false;
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception e) {
            Log.e("AudioManager", "Error releasing media player", e);
        }
        try {
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
        } catch (Exception e) {
            Log.e("AudioManager", "Error releasing recorder", e);
        }
    }
}
