package com.demo.opencv.textEntry;
import android.content.Context;
import android.text.TextUtils;

import com.demo.opencv.R;
import com.demo.opencv.personal.PersonalLexicon;

import java.util.Collections;
import java.util.List;

public class QuickChatManager {

    com.demo.opencv.other.AudioManager audioManager = new com.demo.opencv.other.AudioManager();
    public QuickChatData quickChatData = new QuickChatData();
    Context mContext;
    int mode = 0; // 0 = home page, 1-5 = the 5 different categories
    int MAX_MODES = 5;
    static final int MY_PHRASES_MODE = 6; // the sentences this patient speaks most, learned from the typing modes
    private PersonalLexicon personalLexicon; // null without a context
    private String[] myPhrases = new String[0]; // fixed while the page is open so entries do not move under the user's gaze
    String[] modeStrings = {
            String.valueOf(R.string.QuickChat_MyPhrases),
            String.valueOf(R.string.QuickChat_Category1),
            String.valueOf(R.string.QuickChat_Category2),
            String.valueOf(R.string.QuickChat_Category3),
            String.valueOf(R.string.QuickChat_Category4),
            String.valueOf(R.string.QuickChat_Category5)
    }; // the title of each category
    public void setPersonalLexicon(PersonalLexicon personalLexicon) {
        this.personalLexicon = personalLexicon;
    }

    public void initialize(Context mContext) {
        this.mContext = mContext;
        if (mContext != null) personalLexicon = PersonalLexicon.get(mContext);
        audioManager.initialize(mContext);
        if (quickChatData == null) {
            quickChatData = new QuickChatData();
        }
        updateDisplays();
    }

    public void manageUserInput(int input, boolean isGazeType) {
        // left gaze = 3, right gaze = 5, up gaze = 0, down gaze = -1, closed = 4, left-up gaze = 1, right-up gaze = 2
        int selection = input;
        if (isGazeType) { // if is gaze type, then process it (is false for touchscreen)
            final int[] keyboardInputMat = {-1, 3, 5, 0, -1, 4, 1, 2}; // convert gaze type into keyboard input
            if (input < 0 || input >= keyboardInputMat.length) return;
            selection = keyboardInputMat[input];
        }

        // up gaze = 0, left gaze = 1, right gaze = 2, closed = 3, left-up gaze = 4, right-up gaze = 5
        if (selection != -1) {
            if (selection == 0) { // back button
                if (mode != 0) {
                    mode = 0; // back to main page from the categories
                } else {
                    openMyPhrases();
                }
            } else if (selection >= 1 && selection <= MAX_MODES) { // only valid 1..5
                if (mode == 0) {
                    mode = selection; // go to the category
                } else {
                    speakText(selection); // speak the text
                }
            }
        }
        updateDisplays();
    }

    public void updateDisplays() {
        if (quickChatData == null) {
            quickChatData = new QuickChatData();
        }
        if (mode == 0) { // main page
            quickChatData.texts = modeStrings;
        } else if (mode >= 1 && mode <= MAX_MODES) { // category page
            String[] newText = new String[6];
            newText[0] = String.valueOf(R.string.QuickChat_BackButton);
            System.arraycopy(quickChatStrings[mode - 1], 0, newText, 1, MAX_MODES);
            quickChatData.texts = newText;
        } else if (mode == MY_PHRASES_MODE) {
            String[] newText = {String.valueOf(R.string.QuickChat_BackButton), "", "", "", "", ""};
            System.arraycopy(myPhrases, 0, newText, 1, myPhrases.length);
            if (myPhrases.length == 0) newText[1] = String.valueOf(R.string.QuickChat_MyPhrasesEmpty);
            quickChatData.texts = newText;
        }
    }

    private void openMyPhrases() {
        List<String> phrases = personalLexicon != null ? personalLexicon.phrases(MAX_MODES) : Collections.emptyList();
        myPhrases = phrases.toArray(new String[0]);
        mode = MY_PHRASES_MODE;
    }

    public void speakText(int number) {
        if (mode == MY_PHRASES_MODE) {
            if (number < 1 || number > myPhrases.length) return;
            String phrase = myPhrases[number - 1];
            if (audioManager != null) audioManager.speakText(phrase);
            if (personalLexicon != null) personalLexicon.recordPhrase(phrase); // keeps the sentences still in use on this page
            return;
        }
        if (mode < 1 || mode > quickChatStrings.length) return;
        if (number < 1 || number > quickChatStrings[mode - 1].length) return;
        String data = quickChatStrings[mode - 1][number - 1];
        if (data != null && TextUtils.isDigitsOnly(data) && mContext != null) { // an id
            try {
                data = mContext.getResources().getString(Integer.parseInt(data));
            } catch (Exception e) {
                // keep data as is
            }
        }
        if (data != null && !data.isEmpty()) {
            if (audioManager != null) {
                audioManager.speakText(data);
            }
        }
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        if (mode >= 0 && mode <= MAX_MODES) {
            this.mode = mode;
            updateDisplays();
        }
    }

    public void onDestroy() {
        if (audioManager != null) {
            audioManager.destroy();
        }
    }

    String[][] quickChatStrings = { // the content stored in the categories (two-dimensional: 5x5)
            {
                    String.valueOf(R.string.QuickChat_Category1_1),
                    String.valueOf(R.string.QuickChat_Category1_2),
                    String.valueOf(R.string.QuickChat_Category1_3),
                    String.valueOf(R.string.QuickChat_Category1_4),
                    String.valueOf(R.string.QuickChat_Category1_5)
            },
            {
                    String.valueOf(R.string.QuickChat_Category2_1),
                    String.valueOf(R.string.QuickChat_Category2_2),
                    String.valueOf(R.string.QuickChat_Category2_3),
                    String.valueOf(R.string.QuickChat_Category2_4),
                    String.valueOf(R.string.QuickChat_Category2_5)
            },
            {
                    String.valueOf(R.string.QuickChat_Category3_1),
                    String.valueOf(R.string.QuickChat_Category3_2),
                    String.valueOf(R.string.QuickChat_Category3_3),
                    String.valueOf(R.string.QuickChat_Category3_4),
                    String.valueOf(R.string.QuickChat_Category3_5)
            },
            {
                    String.valueOf(R.string.QuickChat_Category4_1),
                    String.valueOf(R.string.QuickChat_Category4_2),
                    String.valueOf(R.string.QuickChat_Category4_3),
                    String.valueOf(R.string.QuickChat_Category4_4),
                    String.valueOf(R.string.QuickChat_Category4_5)
            },
            {
                    String.valueOf(R.string.QuickChat_Category5_1),
                    String.valueOf(R.string.QuickChat_Category5_2),
                    String.valueOf(R.string.QuickChat_Category5_3),
                    String.valueOf(R.string.QuickChat_Category5_4),
                    String.valueOf(R.string.QuickChat_Category5_5)
            }
    };
}
