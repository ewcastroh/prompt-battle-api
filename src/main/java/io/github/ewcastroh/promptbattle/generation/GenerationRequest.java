package io.github.ewcastroh.promptbattle.generation;

public record GenerationRequest(String prompt, long seed, int width, int height) {

    public static final int SIZE = 1024;

    public static GenerationRequest square(String prompt, long seed) {
        return new GenerationRequest(prompt, seed, SIZE, SIZE);
    }
}
