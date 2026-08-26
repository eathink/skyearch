package org.eathink.skyearch.util;

import org.eathink.skyearch.http.HttpException;

import java.util.regex.Pattern;

/** Collection / Bucket / Document ID 命名校验(同时防止路径穿越)。 */
public final class Names {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern DOC_ID = Pattern.compile("[A-Za-z0-9_.:\\-]{1,256}");

    private Names() {
    }

    /** Collection / Bucket 名: 字母数字下划线连字符, 最长 128。 */
    public static void checkName(String value, String what) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw HttpException.badRequest(what + " must match [A-Za-z0-9_-]{1,128}");
        }
    }

    /** Document ID: 允许额外的点 / 冒号, 最长 256。 */
    public static void checkDocId(String value) {
        if (value == null || !DOC_ID.matcher(value).matches()) {
            throw HttpException.badRequest("document id must match [A-Za-z0-9_.:-]{1,256}");
        }
    }
}
