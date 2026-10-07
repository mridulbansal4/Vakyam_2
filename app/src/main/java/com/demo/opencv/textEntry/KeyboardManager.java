package com.demo.opencv.textEntry;

public class KeyboardManager {

    String textInput = ""; // stores the final text input
    String[] layer1Options = {"a b c d e f", "g h i j k l", "m,n,o,p,q,r", "s,t,u,v,w,x", "y z , . ! ?", "space delete clear"};
    String[] layer2Options = {"abcdef", "ghijkl", "mnopqr", "stuvwx", "yz,.!?", " *#"};
    int layer = 1;
    int page = 0;

    public KeyboardData getDisplays() {
        KeyboardData keyboardData = new KeyboardData();
        String[] options = new String[6];

        if (layer == 1 || page < 0 || page >= layer2Options.length) {
            layer = 1;
            options = layer1Options;
        } else {
            String selected = layer2Options[page];
            for (int i = 0; i < selected.length() && i < options.length; i++) {
                if (selected.charAt(i) == ' ') { // space operation
                    options[i] = "Add space";
                } else if (selected.charAt(i) == '*') { // delete operation
                    options[i] = "Delete last letter";
                } else if (selected.charAt(i) == '#') { // clear option
                    options[i] = "Clear text";
                } else {
                    options[i] = "" + selected.charAt(i); // letter
                }
            }
        }

        keyboardData.setOptions(options);
        keyboardData.setTextInput(textInput); // set current text entry
        return keyboardData;
    }

    public void processInput(int input, boolean isGazeType) {
        int selection = input;
        if (isGazeType) {
            // left gaze = 1, right gaze = 2, up gaze = 0, down gaze = -1, closed = 3, left-up gaze = 4, right-up gaze = 5
            final int[] keyboardInputMat = {-1, 1, 2, 0, -1, 3, 4, 5}; // convert gaze type into keyboard input
            if (input < 0 || input >= keyboardInputMat.length) return;
            selection = keyboardInputMat[input];
        }
        if (selection < 0 || selection >= layer1Options.length) return;

        if (layer == 1) {
            layer = 2;
            page = selection;
        } else {
            if (page >= 0 && page < layer2Options.length && selection >= 0 && selection < layer2Options[page].length()){
                char operation = layer2Options[page].charAt(selection);
                if (operation == '*') {
                    if (textInput.length() > 0) {
                        textInput = textInput.substring(0, textInput.length() - 1); // delete operation
                    }
                } else if (operation == '#') {
                    textInput = "";
                } else {
                    textInput += operation; // add the letter/space
                }
            }
            layer = 1;
        }
    }

    public void processInput(int selection) {
        processInput(selection, true);
    }

    public String getTextInput() {
        return textInput;
    }

    public void setTextInput(String textInput) {
        this.textInput = (textInput != null) ? textInput : "";
    }

    public int getLayer() {
        return layer;
    }

    public int getPage() {
        return page;
    }

    public void reset() {
        layer = 1;
        page = 0;
        textInput = "";
    }

    public void initialize() {
        reset();
    }

}
