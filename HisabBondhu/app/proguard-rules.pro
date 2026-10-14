# The app serialises records by hand (org.json / Map), so no reflection-based keep rules are needed
# for Firestore models. Room and Compose ship their own consumer rules.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
