package playeresp.inject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class PlayerEspConfig {
    public boolean enabled = true;
    public int boxMode = 1; // 0 off, 1 2D, 2 3D, 3 both
    public float outlineThickness = 2.0F;
    public boolean chams = false;
    public int chamsTransparency = 65;
    public boolean renderNpcs = false;
    public int colorMode = 0; // 0 nametag/team color, 1 custom
    public boolean nametag = true;
    public boolean modifyNametag = true;
    public float nameScale = 1.0F;
    public int healthBarPosition = 2; // 0 off, 1 top, 2 side
    public boolean healthText = true;
    public boolean distance = true;
    public boolean armor = false;
    public boolean heldItem = false;
    public boolean targetHud = true;
    public int maxDistance = 128;
    public int color = 0xFFFF5555;
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
            if (!source.equals(file)) config.save();
            return config;
        } catch (Exception ignored) {
            return new PlayerEspConfig();
        }
    }

    public void save() {
        File file = file();
        File parent = file.getParentFile();
        if (!parent.isDirectory()) parent.mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (Exception ignored) { }
    }
}
