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
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AudioManager {
    MediaPlayer mediaPlayer = new MediaPlayer();
    private SpeechRecognizer speechRecognizer;
    TextToSpeech textToSpeech;
    MediaRecorder recorder;
    private String fileName;
    String finalText;
    private volatile boolean isTtsReady = false;
    private final List<String> pendingUtterances = new ArrayList<>();

    public interface AudioManagerListener {
        void onAudioUpdated(String key, String value);
    }
    AudioManager.AudioManagerListener audioManagerListener;

    public void setAudioManagerListener(AudioManagerListener audioManagerListener) {
        this.audioManagerListener = audioManagerListener;
    }

    public void initialize(Context mContext) {
        if (mContext == null) return;

        textToSpeech = new TextToSpeech(mContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                if (textToSpeech != null) {
                    textToSpeech.setLanguage(Locale.US);
                }
                isTtsReady = true;
                Log.d("AudioManager", "TTS initialized successfully");
                synchronized (pendingUtterances) {
                    for (String utterance : pendingUtterances) {
                        if (textToSpeech != null) {
                            textToSpeech.speak(utterance, TextToSpeech.QUEUE_ADD, null, null);
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
    }

    public void speakText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        Log.d("AudioManager", "Speaking: " + text);
        if (isTtsReady && textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        } else {
            synchronized (pendingUtterances) {
                pendingUtterances.add(text);
            }
        }
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
        if (speechRecognizer == null) return;
        Log.d("AudioManager", "Began recording");
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            Log.e("AudioManager", "startListening error", e);
        }
    }

    public void stopListening() {
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
