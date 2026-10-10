package com.demo.opencv.textEntry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.demo.opencv.AI.GeminiManager;
import com.demo.opencv.UserDataManager;
import com.demo.opencv.other.AudioManager;
import com.demo.opencv.AI.AIManager;
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
    int sentenceGenerationModel = 1; // 0: openAI 3.5, 1: Gemma 4 E2B (Local LLM)
    String language = "English"; // default language
    int mode = 1; // 0: word mode, 1: blurry input
    String context = ""; // the context content for context-based predictions
    KeyboardData keyboardData = new KeyboardData(); // data structure for UI display
    Context mContext;
    AIManager AIManager = new AIManager(); // openAI services
    GeminiManager geminiManager = new GeminiManager(); // gemini services
    AudioManager audioManager = new AudioManager(); // text-to-speech
    BlurryInput blurryInput = new BlurryInput();
    TextInfo current = new TextInfo();

    String NoMatchString = "NO MATCH";
    String NextPageString = "NEXT PAGE";
    String SwitchString = "SWITCH";
    String DeleteString = "DELETE";
    String FinishedString = "FINISH";

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

        AIManager.initialize(mContext);
        audioManager.initialize(mContext);
        current.Sentence = "All ALS patients should have a voice in the digital world.";
        if (mContext != null) {
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mMessageReceiver, new IntentFilter("textGenerationEvent"));
        }

        geminiManager.init(mContext);
    }

    public void updateSettings(Context applicationContext) { // match class settings with the global settings
        if (applicationContext instanceof UserDataManager) {
            UserDataManager userDataManager = (UserDataManager) applicationContext;
            this.language = userDataManager.getLanguage();
            blurryInput.initialize(mContext, language);
            audioManager.setLanguage(language);
            this.sentenceGenerationModel = userDataManager.getSentenceGenerationModel();
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
                if ("Hindi".equals(language)) {
                    hindiMode(selection);
                } else {
                    letterMode(selection);
                }
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

    int hindiState = 1;
    int hindiGroupIdx = 0;
    int hindiBaseIdx = 0;
    int hindiMatraIdx = 0;
    
    String[][] hindiChars = {
        {"अ", "आ", "इ", "ई", "उ", "ऊ", "ए", "ऐ", "ओ", "औ"}, // 0
        {"क", "ख", "ग", "घ", "ङ"}, // 1
        {"च", "छ", "ज", "झ", "ञ", "ट", "ठ", "ड", "ढ", "ण"}, // 2
        {"त", "थ", "द", "ध", "न"}, // 3
        {"प", "फ", "ब", "भ", "म"}, // 4
        {"य", "र", "ल", "व", "श", "ष", "स", "ह", "SPACE"} // 5
    };
    String[] matras = {"", "ा", "ि", "ी", "ु", "ू", "े", "ै", "ो", "ौ", "्", "ं", "ँ", "ृ"};
    String[] hindiState1UI = {"अ-औ", "क-ङ", "च-ण", "त-न", "प-म", "य-ह"};

    void hindiMode(int selection) {
        if (hindiState == 1) {
            hindiGroupIdx = selection;
            hindiBaseIdx = 0;
            hindiMatraIdx = 0;
            hindiState = 2;
        } else if (hindiState == 2) {
            switch (selection) {
                case 0: // Delete / Back
                    if (current.BlurryInput.length() > 0) {
                        current.BlurryInput = current.BlurryInput.substring(0, current.BlurryInput.length() - 1);
                    } else {
                        current.deleteWord();
                        sentencePrediction();
                    }
                    hindiState = 1;
                    break;
                case 1: // Next Matra
                    hindiMatraIdx = (hindiMatraIdx + 1) % matras.length;
                    break;
                case 2: // Next Consonant
                    hindiBaseIdx = (hindiBaseIdx + 1) % hindiChars[hindiGroupIdx].length;
                    hindiMatraIdx = 0;
                    break;
                case 3: // Confirm Char
                    String c = hindiChars[hindiGroupIdx][hindiBaseIdx];
                    if (c.equals("SPACE")) {
                        if (!current.BlurryInput.isEmpty()) {
                            current.addWord(current.BlurryInput);
                            current.clearBlurryInputs();
                        }
                        hindiState = 1;
                        mode = 0;
                    } else {
                        if (hindiGroupIdx != 0) c += matras[hindiMatraIdx];
                        current.addBlurryInput(c);
                        hindiState = 1;
                    }
                    break;
                case 4: // Word 0
                    if (wordPredictions.size() > 0) {
                        current.addWord(wordPredictions.get(0));
                        current.clearBlurryInputs();
                        hindiState = 1;
                        mode = 0;
                    }
                    break;
                case 5: // Word 1
                    if (wordPredictions.size() > 1) {
                        current.addWord(wordPredictions.get(1));
                        current.clearBlurryInputs();
                        hindiState = 1;
                        mode = 0;
                    }
                    break;
            }
        }
        
        String prefix = current.BlurryInput;
        if (hindiState == 2) {
            String c = hindiChars[hindiGroupIdx][hindiBaseIdx];
            if (!c.equals("SPACE")) {
                if (hindiGroupIdx != 0) c += matras[hindiMatraIdx];
                prefix += c;
            }
        }
        if (!prefix.isEmpty()) {
            List<String> matched = blurryInput.getExactMatchingWords(prefix, current.Words);
            wordPredictions = new ArrayList<>();
            wordPredictions.add(prefix);
            if (matched != null) {
                for (String w : matched) {
                    if (!w.equals(prefix)) wordPredictions.add(w);
                }
            }
        } else {
            wordPredictions.clear();
        }
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

        if ("Hindi".equals(language) && mode == 1) {
            if (hindiState == 1) {
                letterModeUI = new String[]{hindiState1UI[0], hindiState1UI[1], hindiState1UI[2], hindiState1UI[3], hindiState1UI[4], hindiState1UI[5], NoMatchString, NoMatchString, NoMatchString, NoMatchString};
            } else {
                String currentChar = hindiChars[hindiGroupIdx][hindiBaseIdx];
                if (!currentChar.equals("SPACE") && hindiGroupIdx != 0) currentChar += matras[hindiMatraIdx];
                String w0 = wordPredictions.size() > 0 ? wordPredictions.get(0) : NoMatchString;
                String w1 = wordPredictions.size() > 1 ? wordPredictions.get(1) : NoMatchString;
                letterModeUI = new String[]{DeleteString, "Next Matra", "Next Char", "Confirm " + currentChar, w0, w1, NoMatchString, NoMatchString, NoMatchString, NoMatchString};
            }
            keyboardData.setOptions(letterModeUI);
        } else {
            switch (mode) {
                case 0 -> keyboardData.setOptions(wordModeUI);
                case 1 -> keyboardData.setOptions(letterModeUI);
            }
        }
        
        // creating the text box for final output
        StringBuilder textBox = current.buildText();
        keyboardData.setTextInput(textBox.toString());
        if (LLMEnabled) {
            keyboardData.sentence = "LLM: " + current.Sentence;
        } else {
            keyboardData.sentence = (mContext != null) ? mContext.getString(R.string.TextEntry_EnableLLM) : "Enable LLM to generate";
        }
        if (context == null || context.isEmpty()) {
            keyboardData.context = (mContext != null) ? mContext.getString(R.string.TextEntry_EnterContext) : "Add Context: ";
        } else {
            keyboardData.context = "Context: " + context;
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
        if (sentenceGenerationModel == 0 && com.demo.opencv.BuildConfig.MY_OPENAI_KEY != null && !com.demo.opencv.BuildConfig.MY_OPENAI_KEY.isEmpty()) { // openAI model
            AIManager.generateText("SP", "Keywords: " + finalString + ". Context: " + context, language);
        } else { // Gemma 4 E2B on-device model
            geminiManager.generate(finalString, context, language);
        }
        current.Sentence = "Generating...";
    }

    public void nextWordPrediction(int num) { // predicting the next word based on current keywords
        if (!LLMEnabled || Objects.equals(language, "Chinese")) return;
        if (!current.Words.isEmpty() && Objects.equals(current.BlurryInput, "")) { // no blurry input, at least one word
            if (sentenceGenerationModel == 1 || com.demo.opencv.BuildConfig.MY_OPENAI_KEY == null || com.demo.opencv.BuildConfig.MY_OPENAI_KEY.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                List<String> fallbackWords = blurryInput.getExactMatchingWords("", current.Words);
                for (int i = 0; i < num && i < fallbackWords.size(); i++) {
                    sb.append(fallbackWords.get(i)).append(i == Math.min(num, fallbackWords.size()) - 1 ? "" : ", ");
                }
                Intent intent = new Intent("textGenerationEvent");
                intent.putExtra("message", "NWP-" + sb.toString());
                if (mContext != null) LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
            } else {
                AIManager.generateText("NWP",
                        "Given the word '" + current.Words.get(current.Words.size() - 1) +
                                "Predict " + num + " " + language + "words that will mostly follow that word in a sentence" +
                                "Only output in the format: word, word, word.", language
                );
            }
        }
    }

    public void contextWordPrediction(int number) {
        if (!LLMEnabled || Objects.equals(language, "Chinese")) return;
        if (!Objects.equals(context, "")) { // if there is context
            if (sentenceGenerationModel == 1 || com.demo.opencv.BuildConfig.MY_OPENAI_KEY == null || com.demo.opencv.BuildConfig.MY_OPENAI_KEY.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                List<String> fallbackWords = blurryInput.getExactMatchingWords("", current.Words);
                for (int i = 0; i < number && i < fallbackWords.size(); i++) {
                    sb.append(fallbackWords.get(i)).append(i == Math.min(number, fallbackWords.size()) - 1 ? "" : ", ");
                }
                Intent intent = new Intent("textGenerationEvent");
                intent.putExtra("message", "CWP-" + sb.toString());
                if (mContext != null) LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
            } else {
                AIManager.generateText("CWP",
                        "You are predicting words for a " + language + " motor neuron disease patient. " +
                                "Give me " + number + " specific or proper nouns in " + language + " that might be part of a response to the " + language + " phrase:" + context +
                                "during a conversation. " +
                                "ONLY output in the format: word, word, word.", language
                );
            }
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
        if (audioManager != null) {
            audioManager.speakText(text); // on-device speech
        }
        AIManager.speechGenerationService(text); // optional secondary
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
            } catch (Exception e) {
                Log.w("TextEntryManager", "Error unregistering receiver", e);
            }
        }
        if (audioManager != null) {
            audioManager.destroy();
        }
    }
}
