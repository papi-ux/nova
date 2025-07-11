Quick-start guide: writing JVM/Robolectric unit tests for this repo
===================================================================

0.  Where tests live  
    • Path: `app/src/test/java/com/limelight/...` (plain JVM tests, *not* instrumented).  
    • Build task:  
      – Default flavour: `./gradlew :app:testNonRoot_gameDebugUnitTest`  
      – Root flavour:   `./gradlew :app:testRootDebugUnitTest`  
      – All:           `./gradlew :app:testDebugUnitTest`

1.  Dependencies & Gradle switches  
    `app/build.gradle` already contains everything you need:  

        testImplementation 'junit:junit:4.13.2'
        testImplementation 'androidx.test:core:1.5.0'
        testImplementation 'org.robolectric:robolectric:4.11.1'
        testImplementation 'org.mockito:mockito-core:5.11.0'

    Extra flag:  

        testOptions.unitTests.includeAndroidResources = true  

    → Tells Robolectric to merge `res/` into the unit-test APK so layout inflation works.

2.  Test-class boilerplate  

    ```java
    @Config(sdk = {33},
            shadows = {
                com.limelight.shadows.ShadowMoonBridge.class,
                com.limelight.shadows.ShadowGameManager.class})
    @RunWith(RobolectricTestRunner.class)
    public class MyFeatureTest {
        private Context ctx;

        @BeforeClass
        public static void silenceLogs() {
            TestLogSuppressor.install();   // hides noisy “Invalid ID 0x00000000” spam
        }

        @Before
        public void setUp() {
            ctx = ApplicationProvider.getApplicationContext();
            // extra prep (clear prefs, reset singletons, etc.)
        }

        @Test
        public void something_should_work() {
            /* your assertions */
        }
    }
    ```

    • `@Config(sdk = {33})` makes Robolectric emulate Android 13 (matches `compileSdk 34` while staying stable).  
    • `shadows = …` suppresses native or platform calls:

      – `ShadowMoonBridge` eliminates the static initializer that tries `System.loadLibrary("moonbr")`, and provides minimal stubs/constants used by the app.  
      – `ShadowGameManager` avoids a ServiceManager lookup present on real devices.

    If you hit another native/SDK obstacle, create a new shadow the same way:

    ```java
    @Implements(SomeProblematicClass.class)
    public class ShadowFoo {
        @Implementation protected static void __staticInitializer__() {}
    }
    ```

3.  Typical helpers used in current tests  

    • **Resetting singletons** – many app classes use `private static` caches.  
      Example (ProfilesManager):

      ```java
      Field f = ProfilesManager.class.getDeclaredField("instance");
      f.setAccessible(true);
      f.set(null, null);       // clear before each test
      ```

    • **Cleaning filesystem state** – tests create/delete `context.getFilesDir()/profiles`.  
      Re-use `deleteRecursively(File)` from existing tests.

    • **Preferences isolation** – wipe them in `@Before`:

      ```java
      SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
      p.edit().clear().commit();
      ```

    • **Activity testing** – use the Robolectric `buildActivity` chain:

      ```java
      MyActivity act = Robolectric.buildActivity(MyActivity.class)
                                  .create().start().resume().get();
      assertFalse(act.isFinishing());
      ```

    • **Intent extras check**: supply them directly to `buildActivity`.  
    • **Configuration changes**: call `activity.onConfigurationChanged(newConfig)` manually.  
    • **Low-memory / lifecycle**: `activity.onLowMemory()`, `activity.onDestroy()`, etc.

4.  Mockito (rarely used yet)  
    If a new test needs mocks (e.g. for `NvConnection`), just:

    ```java
    NvConnection conn = Mockito.mock(NvConnection.class);
    Mockito.when(conn.sendUtf8Text(Mockito.anyString())).thenReturn(0);
    ```

5.  Tips & Gotchas specific to this project  

    • **Native JNI classes** (MoonBridge, etc.) must *always* be shadowed or the test JVM will `UnsatisfiedLinkError`.  
    • **GameManager & BackdropFrameRenderer** are Android-T APIs; stubbing them prevents `ServiceManager` or `SurfaceFlinger` lookups.  
    • **Resources** – because `includeAndroidResources=true`, you can safely inflate real layout XML, but keep the SDK level in `@Config` ≥ the latest attribute you reference.  
    • **Product flavours** – tests are compiled once per flavour; don’t hard-code `BuildConfig.APPLICATION_ID`, use `context.getPackageName()` when needed.  
    • **Suppress noisy log spam** – call `TestLogSuppressor.install()` once per test-class (see above).  

6.  Skeleton for a new test file  

    ```
    app/
      src/
        test/
          java/
            com/
              limelight/
                myfeature/
                  AwesomeFeatureTest.java   <-- new
    ```

    ```java
    package com.limelight.myfeature;

    import android.content.Context;
    import androidx.test.core.app.ApplicationProvider;
    import com.limelight.TestLogSuppressor;
    import org.junit.*;
    import org.robolectric.*;
    import org.robolectric.annotation.Config;

    @Config(sdk = {33},
            shadows = {com.limelight.shadows.ShadowMoonBridge.class,
                       com.limelight.shadows.ShadowGameManager.class})
    @RunWith(RobolectricTestRunner.class)
    public class AwesomeFeatureTest {
        private Context ctx;

        @BeforeClass
        public static void init() { TestLogSuppressor.install(); }

        @Before
        public void setUp() { ctx = ApplicationProvider.getApplicationContext(); }

        @Test
        public void newFeature_doesSomething() {
            // Arrange

            // Act

            // Assert
            Assert.assertTrue(true);
        }
    }
    ```

With the above conventions—Robolectric runner, shadows for native pieces, reflection resets,
and resource-enabled unit tests—you can write new coverage quickly without needing the
Android emulator or `androidTest` instrumentation.

```java
    @Config(sdk = {33},
            shadows = {
                com.limelight.shadows.ShadowMoonBridge.class,
                com.limelight.shadows.ShadowGameManager.class})
    @RunWith(RobolectricTestRunner.class)
    public class MyFeatureTest {
        private Context ctx;

        @BeforeClass
        public static void silenceLogs() {
            TestLogSuppressor.install();   // hides noisy “Invalid ID 0x00000000” spam
        }

        @Before
        public void setUp() {
            ctx = ApplicationProvider.getApplicationContext();
            // extra prep (clear prefs, reset singletons, etc.)
        }

        @Test
        public void something_should_work() {
            /* your assertions */
        }
    }
```

```java
    @Implements(SomeProblematicClass.class)
    public class ShadowFoo {
        @Implementation protected static void __staticInitializer__() {}
    }
```

```java
      Field f = ProfilesManager.class.getDeclaredField("instance");
      f.setAccessible(true);
      f.set(null, null);       // clear before each test
```

```java
      SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
      p.edit().clear().commit();
```

```java
      MyActivity act = Robolectric.buildActivity(MyActivity.class)
                                  .create().start().resume().get();
      assertFalse(act.isFinishing());
```

```java
    NvConnection conn = Mockito.mock(NvConnection.class);
    Mockito.when(conn.sendUtf8Text(Mockito.anyString())).thenReturn(0);
```

```plaintext
    app/
      src/
        test/
          java/
            com/
              limelight/
                myfeature/
                  AwesomeFeatureTest.java   <-- new
```

```java
    package com.limelight.myfeature;

    import android.content.Context;
    import androidx.test.core.app.ApplicationProvider;
    import com.limelight.TestLogSuppressor;
    import org.junit.*;
    import org.robolectric.*;
    import org.robolectric.annotation.Config;

    @Config(sdk = {33},
            shadows = {com.limelight.shadows.ShadowMoonBridge.class,
                       com.limelight.shadows.ShadowGameManager.class})
    @RunWith(RobolectricTestRunner.class)
    public class AwesomeFeatureTest {
        private Context ctx;

        @BeforeClass
        public static void init() { TestLogSuppressor.install(); }

        @Before
        public void setUp() { ctx = ApplicationProvider.getApplicationContext(); }

        @Test
        public void newFeature_doesSomething() {
            // Arrange

            // Act

            // Assert
            Assert.assertTrue(true);
        }
    }
```
