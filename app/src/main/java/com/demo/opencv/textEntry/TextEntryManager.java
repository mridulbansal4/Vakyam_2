package com.demo.opencv.textEntry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.demo.opencv.AI.GeminiManager;
import com.demo.opencv.BuildConfig;
import com.demo.opencv.UserDataManager;
import com.demo.opencv.metrics.TypingTest;
import com.demo.opencv.personal.PersonalLexicon;
import com.demo.opencv.other.AudioManager;
import com.demo.opencv.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class TextEntryManager {
    public boolean LLMEnabled = true;
    List<String> wordPredictions = new ArrayList<>(); // the word predictions shown in word mode
    int wordsPerPage = 3;
    int NWPWords = 6;
    int currentPage = 1;
    String language = "English"; // default language
    int mode = 1; // 0: word mode, 1: blurry input
    String context = ""; // the context content for context-based predictions
    KeyboardData keyboardData = new KeyboardData(); // data structure for UI display
    Context mContext;
    GeminiManager geminiManager = new GeminiManager(); // gemini services
    AudioManager audioManager = new AudioManager(); // text-to-speech
    BlurryInput blurryInput = new BlurryInput();
    TextInfo current = new TextInfo();
    PersonalLexicon personalLexicon; // the patient's own words and sentences, null without a context
    private String lastLearned = ""; // what was last taught to the lexicon, so repeating a sentence aloud is not counted twice
    HinglishDetector hinglishDetector = HinglishDetector.get(null);
    String lastSpokenLanguage = HinglishDetector.ENGLISH; // what Language = English last spoke in: English or Hinglish
    String llmLanguage = HinglishDetector.ENGLISH; // what the last sentence was generated in
    static final String GENERATING = "Generating...";

    String NoMatchString = "No match";
    String NextPageString = "Next page";
    String SwitchString = "Switch";
    String DeleteString = "Delete";
    String FinishedString = "Finish";

    String[] wordModeUI;
    int[] wordIndex = {-1, 2, -1, -1, 0, 1, -1, -1, -1, -1};

    String[] letterModeUI;
    int[] wordIndex2 = {-1, -1, -1, -1, -1, -1, 2, -1, 0, 1};

    public TextEntryManager() {
        initUIStrings();
    }

    private void initUIStrings() {
        wordModeUI = new String[]{FinishedString, NoMatchString, NextPageString, SwitchString, NoMatchString, NoMatchString, "NOPQRST", "UVWXYZ", "ABCDEF", "GHIJKLM"};
        letterModeUI = new String[]{DeleteString, "NOPQRST", "UVWXYZ", SwitchString, "ABCDEF", "GHIJKLM", NoMatchString, NoMatchString, NoMatchString, NoMatchString};
    }

    public void initialize(Context mContext) throws IOException {
        this.mContext = mContext;
        if (mContext != null) {
            try {
                NoMatchString = mContext.getString(R.string.TextEntry_NoMatch);
                NextPageString = mContext.getString(R.string.TextEntry_NextPage);
                SwitchString = mContext.getString(R.string.TextEntry_Switch);
                DeleteString = mContext.getString(R.string.TextEntry_Delete);
                FinishedString = mContext.getString(R.string.TextEntry_Finished);
            } catch (Exception e) {
                Log.w("TextEntryManager", "Could not load string resources", e);
            }
        }
        initUIStrings();
        if (mContext != null) {
            personalLexicon = PersonalLexicon.get(mContext);
            blurryInput.setPersonalLexicon(personalLexicon);
            hinglishDetector = HinglishDetector.get(mContext);
            lastSpokenLanguage = HinglishDetector.lastSpoken(mContext);
            blurryInput.setLastSpokenLanguage(lastSpokenLanguage);
        }

        audioManager.initialize(mContext);
        current.Sentence = "All ALS patients should have a voice in the digital world.";
        if (mContext != null) {
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mMessageReceiver, new IntentFilter("textGenerationEvent"));
            if (BuildConfig.DEBUG) {
                ContextCompat.registerReceiver(mContext, debugSpeakReceiver, new IntentFilter(DEBUG_SPEAK_ACTION),
                        ContextCompat.RECEIVER_EXPORTED);
            }
        }

        geminiManager.init(mContext);
    }

    // Debug builds only: adb shell am broadcast -a com.vakyam.debug.SPEAK --es text "Mujhe paani chahiye"
    // or, to stand in for what the mic heard: --es context "What would you like to eat?"
    static final String DEBUG_SPEAK_ACTION = "com.vakyam.debug.SPEAK";
    private final BroadcastReceiver debugSpeakReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("context")) {
                updateContext(intent.getStringExtra("context"));
                return;
            }
            String text = intent.getStringExtra("text");
            Log.i("TextEntryManager", "debug speak: voice=" + speak(text) + " text=\"" + text + "\"");
        }
    };

    public void updateSettings(Context applicationContext) { // match class settings with the global settings
        if (applicationContext instanceof UserDataManager) {
            UserDataManager userDataManager = (UserDataManager) applicationContext;
            this.language = userDataManager.getLanguage();
            blurryInput.initialize(mContext, language);
            blurryInput.setLastSpokenLanguage(lastSpokenLanguage);
            audioManager.setLanguage(language);
            this.LLMEnabled = (userDataManager.getTextEntryMode() == 2);
            if (userDataManager.getTextEntryContext() != null) {
                updateContext(userDataManager.getTextEntryContext());
            }
        }
    }

    public void manageUserInput(int input, boolean isGazeType) {
        // left gaze = 1, right gaze = 2, up gaze = 0, down gaze = -1, closed = 3, left-up gaze = 4, right-up gaze = 5
        int selection = input;
        if (isGazeType) { // if is gaze type, then process it
            final int[] keyboardInputMat = {-1, 1, 2, 0, -1, 3, 4, 5}; // convert gaze type into keyboard input
            if (input < 0 || input >= keyboardInputMat.length) return;
            selection = keyboardInputMat[input];
        }

        if (selection != -1) {
            if (keyboardData.finished) {
                finishedMode(selection); // finished screen
            } else if (mode == 0) { // baseline text-entry, mode 0
                WordMode(selection);
            } else if (mode == 1){ // baseline text-entry, mode 1
                letterMode(selection);
            }
        }
        updateDisplays();
    }

    void WordMode(int selection) { // when the user is typing words
        switch (selection) {
            case 0 -> keyboardData.finished = true; // open finished page
            case 1 -> {selectWord(2); nextWordPrediction(NWPWords); sentencePrediction(); }
            case 2 -> nextPage();
            case 3 -> {mode = 1; currentPage = 1;}
            case 4 -> {selectWord(0); nextWordPrediction(NWPWords); sentencePrediction(); }
            case 5 -> {selectWord(1); nextWordPrediction(NWPWords); sentencePrediction(); }
        }
    }

    void finishedMode(int selection) { // when the user finished text entry
        switch (selection) {
            case 1 -> utterText(); // speak text
            case 5 -> keyboardData.finished = false; // return to typing
            default -> {} // selection 2 (formerly upload) and other inputs are safe no-ops
        }
    }

    void letterMode(int selection) {
        switch (selection) {
            case 0 -> { // delete blurry input or word
                TypingTest.onCorrection();
                if (Objects.equals(current.BlurryInput, "")) {
                    current.deleteWord();
                    sentencePrediction();
                } else {
                    current.deleteBlurryInput();
                }
            }
            case 1 -> current.addBlurryInput("2");
            case 2 -> current.addBlurryInput("3");
            case 3 -> {mode = 0; currentPage = 1;}
            case 4 -> current.addBlurryInput("0");
            case 5 -> current.addBlurryInput("1");
        }
        if (!Objects.equals(current.BlurryInput, "")) { // blurry input not empty
            List<String> matched = blurryInput.getMatchingWords(current.BlurryInput, current.Words);
            wordPredictions = (matched != null) ? matched : new ArrayList<>();
        } else {
            wordPredictions.clear();
        }
    }

    public String getLLMSentence() {
        return current.Sentence;
    }

    public void updateDisplays() {
        if (!wordPredictions.isEmpty()) {
            String[] nextPageWords = blurryInput.getWordPage(wordPredictions, currentPage, wordsPerPage);
            wordModeUI = fillWords(nextPageWords, wordIndex, wordModeUI);
            letterModeUI = fillWords(nextPageWords, wordIndex2, letterModeUI);
        } else {
            // Reset placeholders when prediction list is empty
            resetOptionWords(wordModeUI, wordIndex);
            resetOptionWords(letterModeUI, wordIndex2);
        }

        switch (mode) {
            case 0 -> keyboardData.setOptions(wordModeUI);
            case 1 -> keyboardData.setOptions(letterModeUI);
        }

        // creating the text box for final output
        StringBuilder textBox = current.buildText();
        keyboardData.setTextInput(textBox.toString());
        if (LLMEnabled) {
            keyboardData.sentence = (HinglishDetector.HINGLISH.equals(llmLanguage) ? "LLM (Hinglish): " : "LLM: ") + current.Sentence;
        } else {
            keyboardData.sentence = (mContext != null) ? mContext.getString(R.string.TextEntry_EnableLLM) : "Enable LLM to generate";
        }
        if (context == null || context.isEmpty()) {
            keyboardData.context = "";
        } else {
            keyboardData.context = (mContext != null) ? mContext.getString(R.string.TextEntry_HeardContext, context) : "Heard: " + context;
        }
    }

    private void resetOptionWords(String[] options, int[] indexMap) {
        if (options == null || indexMap == null) return;
        for (int i = 0; i < indexMap.length && i < options.length; i++) {
            if (indexMap[i] != -1) {
                options[i] = NoMatchString;
            }
        }
    }

    public void updateContext(String newContext) {
        Log.d("TextGeneration", "Context updated: " + newContext);
        context = (newContext != null) ? newContext : "";
        contextWordPrediction(50);
        updateDisplays();
    }

    public KeyboardData getDisplays() {
        return this.keyboardData;
    }

    private void sentencePrediction() { // predicting the sentences based on keywords
        if (!LLMEnabled || current.Words.isEmpty()) return; // LLM enabled, at least one word
        String finalString = current.getWords();
        llmLanguage = hinglishDetector.resolve(language, current.Words, context, lastSpokenLanguage);
        geminiManager.generate(finalString, context, llmLanguage);
        current.Sentence = GENERATING;
    }

    public void nextWordPrediction(int num) { // predicting the next word based on current keywords
        if (!LLMEnabled) return;
        if (!current.Words.isEmpty() && Objects.equals(current.BlurryInput, "")) { // no blurry input, at least one word
            StringBuilder sb = new StringBuilder();
            List<String> fallbackWords = blurryInput.getExactMatchingWords("", current.Words);
            for (int i = 0; i < num && i < fallbackWords.size(); i++) {
                sb.append(fallbackWords.get(i)).append(i == Math.min(num, fallbackWords.size()) - 1 ? "" : ", ");
            }
            Intent intent = new Intent("textGenerationEvent");
            intent.putExtra("message", "NWP-" + sb.toString());
            if (mContext != null) LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        }
    }

    public void contextWordPrediction(int number) {
        if (!LLMEnabled) return;
        if (!Objects.equals(context, "")) { // if there is context
            StringBuilder sb = new StringBuilder();
            List<String> fallbackWords = blurryInput.getExactMatchingWords("", current.Words);
            for (int i = 0; i < number && i < fallbackWords.size(); i++) {
                sb.append(fallbackWords.get(i)).append(i == Math.min(number, fallbackWords.size()) - 1 ? "" : ", ");
            }
            Intent intent = new Intent("textGenerationEvent");
            intent.putExtra("message", "CWP-" + sb.toString());
            if (mContext != null) LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        }
    }

    private final BroadcastReceiver mMessageReceiver = new BroadcastReceiver() { // tag 1: SP = sentence prediction, NWP = next word prediction, CWP = context word prediction
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String output = intent.getStringExtra("message");
            if (output != null) {
                String[] splitArray = output.split("-", 2);
                if (splitArray.length < 2) {
                    Log.w("TextGeneration", "Ignoring malformed broadcast: " + output);
                    return;
                }
                String tag = splitArray[0]; // identifier to check the usage of the generation
                String content = splitArray[1];

                Log.d("TextGeneration", "Data Received, " + tag + ": " + content);

                if (Objects.equals(tag, "SP")) { // sentence prediction: SP
                    current.Sentence = content;

                } else if (Objects.equals(tag, "NWP")) { // next word prediction: NWP
                    wordPredictions.clear();
                    String[] wordChoices = content.split(", "); // there should be n words, separated by comma
                    for (int i = 0; i < wordChoices.length; i++) {
                        wordPredictions.add(wordChoices[i]);
                    }

                } else if (Objects.equals(tag, "CWP")) { // context word prediction
                    String[] wordChoices = content.split(", ");
                    blurryInput.extendedWordList.clear();
                    blurryInput.extendedWordList.addAll(Arrays.asList(wordChoices));
                }
                updateDisplays();
            } else {
                Log.d("TextGeneration", "Fail to get data in text entry manager");
            }
        }
    };

    void selectWord(int index) {
        if (index < 0) return;
        int wordIndex = index + (currentPage - 1) * wordsPerPage;
        if (wordIndex >= 0 && wordPredictions.size() > wordIndex) { // input is valid
            current.addWord(wordPredictions.get(wordIndex));
            current.clearBlurryInputs();
        }
        currentPage = 1;
    }

    String[] fillWords(String[] words, int[] wordIndex, String[] options) { // fill words in a UI page
        if (words == null || options == null || wordIndex == null) return options;
        Log.d("TextMode", Arrays.toString(words));
        for (int i = 0; i < wordIndex.length && i < options.length; i++) {
            if (wordIndex[i] != -1) {
                if (wordIndex[i] >= 0 && wordIndex[i] < words.length && words[wordIndex[i]] != null) {
                    options[i] = words[wordIndex[i]];
                } else {
                    options[i] = NoMatchString;
                }
            }
        }
        return options;
    }

    void nextPage() {
        String[] words = blurryInput.getWordPage(wordPredictions, currentPage + 1, wordsPerPage);
        if (words == null) { // page doesn't exist
            Log.d("TextMode", "Max page reached");
            currentPage = 1;
        } else {
            Log.d("TextMode", "next page available");
            currentPage += 1; // add the page
        }
    }

    void utterText() {
        String text;
        if (LLMEnabled) {
            text = current.Sentence;
        } else {
            text = current.getWords();
        }
        speak(text);
        learnUtterance(text);
        TypingTest.onSpoken(text, LLMEnabled ? TypingTest.MODE_GAZE_LLM : TypingTest.MODE_GAZE_AMBIGUOUS);
    }

    // The voice follows the sentence: Hinglish is spoken by the Hindi voice even with Language = English.
    private String speak(String text) {
        String voice = hinglishDetector.resolveText(language, text, lastSpokenLanguage);
        if (audioManager != null) {
            audioManager.speakText(text, voice); // on-device speech
        }
        if (text != null && !text.trim().isEmpty() && !GENERATING.equals(text)) {
            lastSpokenLanguage = voice;
            blurryInput.setLastSpokenLanguage(voice);
            HinglishDetector.rememberSpoken(mContext, voice);
        }
        return voice;
    }

    // Speaking is the confirmation that the words were right, so that is when they are learned.
    private void learnUtterance(String spoken) {
        if (personalLexicon == null || current.Words.isEmpty() || spoken == null || GENERATING.equals(spoken)) return;
        String signature = current.getWords() + "|" + spoken;
        if (signature.equals(lastLearned)) return;
        lastLearned = signature;
        personalLexicon.recordUtterance(new ArrayList<>(current.Words), spoken);
    }

    public void clearText() {
        current.clearAll();
        wordPredictions.clear();
        keyboardData.finished = false;
        mode = 1;
        currentPage = 1;
        updateDisplays();
    }

    public void finishTypingTest() {
        TypingTest.onSpoken(LLMEnabled ? current.Sentence : current.getWords(),
                LLMEnabled ? TypingTest.MODE_GAZE_LLM : TypingTest.MODE_GAZE_AMBIGUOUS);
    }

    public TextInfo getCurrent() {
        return current;
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        this.mode = mode;
        updateDisplays();
    }

    public void onDestroy() {
        if (mContext != null && mMessageReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mMessageReceiver);
                if (BuildConfig.DEBUG) mContext.unregisterReceiver(debugSpeakReceiver);
            } catch (Exception e) {
                Log.w("TextEntryManager", "Error unregistering receiver", e);
            }
        }
        if (audioManager != null) {
            audioManager.destroy();
        }
    }
}
