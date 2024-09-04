package io.github.fatihcatalkaya.javatypst;

import io.questdb.jar.jni.JarJniLoader;

public class JavaTypst {
    static {
        JarJniLoader.loadLib(JavaTypst.class, "/io/github/fatihcatalkaya/javatypst/libs", "java_typst");
    }

    /**
     * This function renders a string containing Typst markup. The resulting pdf file
     * is returned as a byte array after successful rendering.
     * @param content Typst markup
     * @return pdf file byte array
     */
    public static native byte[] render(String content);
}
