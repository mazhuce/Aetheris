package com.aetheris.auth.validation;

/** Auth's existing 64 UTF-16 limit and pipe ban, plus the v2 proof Unicode exclusions. No normalization. */
public final class UsernameRules {
    private UsernameRules() { }
    public static boolean isValid(String value) {
        if(value==null||value.isEmpty()||value.length()>64)return false;
        for(int offset=0;offset<value.length();) {
            int point=value.codePointAt(offset);
            if(point=='|'||Character.isISOControl(point)||Character.isWhitespace(point)||Character.isSpaceChar(point)
                    ||Character.getType(point)==Character.FORMAT||(point>=0xD800&&point<=0xDFFF))return false;
            offset+=Character.charCount(point);
        }
        return true;
    }
    /** Also available to read-only compatibility checks; never trims or rewrites stored account names. */
    public static String requireValid(String value) {
        if(!isValid(value))throw new IllegalArgumentException("用户名须为1至64个字符，且不得包含空白、控制符、格式字符、|或无效Unicode字符");
        return value;
    }
}
