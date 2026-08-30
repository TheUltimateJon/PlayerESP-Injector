package playeresp.inject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class PlayerEspConfig {
    public boolean enabled = true;
    public int boxMode = 1; // 0 off, 1 2D, 2 3D, 3 player outline
    public float outlineThickness = 2.0F;
    public boolean chams = false;
    public boolean renderNpcs = false;
    public int colorMode = 1; // 0 nametag/team color, 1 custom
    public boolean nametag = true;
    public boolean nametagBackground = true;
    public int healthBarPosition = 2; // 0 off, 1 top, 2 side
    public boolean healthText = true;
    public boolean healthTextBackground = true;
    public boolean distance = true;
    public boolean armor = false;
    public boolean heldItem = false;
    public boolean targetHud = true;
    public int maxDistance = 128;
    public int color = 0xFFAAAAAA;
    public int menuKey = 43;
    public int toggleKey = 0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "Toolbox/playeresp_config.js");
    }

    private static File legacyFile() {
        return new File(Minecraft.getMinecraft().mcDataDir, "PlayerESP/config.json");
    }

    public static PlayerEspConfig load() {
        File file = file();
        File source = file.isFile() ? file : legacyFile();
        if (!source.isFile()) {
            PlayerEspConfig config = new PlayerEspConfig();
            config.save();
            return config;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8)) {
            PlayerEspConfig config = GSON.fromJson(reader, PlayerEspConfig.class);
            if (config == null) config = new PlayerEspConfig();
            config.sanitize();
            if (config.colorMode == 1 && config.color == 0xFFFF5555) config.color = 0xFFAAAAAA;
            if (!source.equals(file)) config.save();
            return config;
        } catch (Exception ignored) {
            return new PlayerEspConfig();
        }
    }

    public void save() {
        sanitize();
        File file = file();
        File parent = file.getParentFile();
        if (!parent.isDirectory()) parent.mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (Exception ignored) { }
    }

    private void sanitize() {
        if (boxMode < 0 || boxMode > 3) boxMode = 1;
        if (healthBarPosition < 0 || healthBarPosition > 2) healthBarPosition = 2;
        if (colorMode < 0 || colorMode > 1) colorMode = 1;
        if (!Float.isFinite(outlineThickness)) outlineThickness = 2.0F;
        outlineThickness = Math.max(0.5F, Math.min(5.0F, outlineThickness));
        maxDistance = Math.max(8, Math.min(256, maxDistance));
        menuKey = validKey(menuKey) ? menuKey : 43;
        toggleKey = validKey(toggleKey) ? toggleKey : 0;
        color |= 0xFF000000;
    }

    private static boolean validKey(int key) { return key >= 0 && key < 256; }
}
