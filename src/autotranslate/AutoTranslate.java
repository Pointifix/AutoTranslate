package autotranslate;

import arc.Core;
import arc.func.Cons;
import arc.scene.event.*;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.TextButton;
import arc.struct.Seq;
import arc.util.*;
import com.deepl.api.*;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.FullTextDialog;
import mindustry.ui.dialogs.SettingsMenuDialog;
import mindustry.ui.fragments.ChatFragment;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static arc.Core.bundle;
import static mindustry.Vars.ui;

public class AutoTranslate extends Mod {
    Seq<String> messages;
    Seq<String> processedMessages = new Seq<>();

    @Override
    public void init() {
        Cons<SettingsMenuDialog.SettingsTable> builder = settingsTable -> {
            AtomicReference<String> authKey = new AtomicReference<>(Core.settings.getString("auth-key", ""));
            AtomicReference<String> targetLanguage = new AtomicReference<>(Core.settings.getString("target-language", "en-GB"));

            SettingsMenuDialog.SettingsTable settings = new SettingsMenuDialog.SettingsTable();
            settings.areaTextPref(bundle.get("auto-translate.settings.auth-key"), "", authKey::set);
            settings.textPref(bundle.get("auto-translate.settings.target-language"), "en-GB", targetLanguage::set);

            settings.pref(new ButtonSetting("Save", () -> {
                try {
                    Translator translator = new Translator(authKey.get());

                    if (translator.getTargetLanguages().stream().anyMatch(l -> l.getCode().equals(targetLanguage.get()))) {
                        Core.settings.put("auth-key", authKey.get());
                        Core.settings.put("target-language", targetLanguage.get());

                        showDialog("Auto Translation", "Successfully configured Auto Translate");
                    } else {
                        StringBuilder languages = new StringBuilder("Available languages (enter language code):\n\n");

                        for (Language l : translator.getTargetLanguages()) {
                            languages.append(l.getName()).append(" - ").append(l.getCode()).append("\n");
                        }

                        showDialog("Error", languages.toString());
                    }
                } catch (IllegalArgumentException | DeepLException | InterruptedException e) {
                    showDialog("Error", "Something went wrong: " + e.getMessage());
                    Log.err("Auto Translate: " + e.getMessage());
                }
            }));

            settings.checkPref(bundle.get("auto-translate.settings.enabled"), true, e -> Core.settings.put("auto-translate-enabled", e));

            settingsTable.add(settings);
        };
        ui.settings.getCategories().add(new SettingsMenuDialog.SettingsCategory(bundle.get("auto-translate.settings.title"), new TextureRegionDrawable(Core.atlas.find("auto-translate-logo")), builder));

        String authKey = Core.settings.getString("auth-key", "");

        if (authKey.isEmpty()) {
            showDialog("Auto Translate", "Failed to initialize, invalid or empty authentication key, go to settings and enter a valid Deepl authentication key");
        }

        try {
            Translator translator = new Translator(authKey);

            try {
                Field messagesField = ChatFragment.class.getDeclaredField("messages");
                messagesField.setAccessible(true);

                messages = (Seq<String>) messagesField.get(Vars.ui.chatfrag);

                Timer.schedule(() -> {
                    if (Core.settings.getBool("auto-translate-enabled", true)) {
                        while (processedMessages.size < messages.size) {
                            String message = messages.get(messages.size - processedMessages.size - 1);
                            processedMessages.add(message);
                            message = Strings.stripColors(message);
                            if (message.isEmpty() || !message.startsWith("[") || message.contains(Strings.stripColors(Vars.player.name())))
                                continue;

                            if (message.contains("]")) message = message.substring(message.indexOf("]"));

                            String finalMessage = message;
                            Threads.thread(() -> {
                                try {
                                    String language = Core.settings.getString("target-language", "en-GB");

                                    TextResult result = translator.translateText(finalMessage, null, language);

                                    if (result.getDetectedSourceLanguage().equals(LanguageCode.standardize(language))) return;

                                    String translation = "[cyan]Translation: [gray]" + result.getText() + " [lightgray] - (" + result.getDetectedSourceLanguage() + ")";
                                    processedMessages.add(translation);
                                    Vars.player.sendMessage(translation);
                                } catch (DeepLException | InterruptedException e) {
                                    Log.err("Failed to translate message: " + e.getMessage());
                                    Vars.player.sendMessage("[red]Failed to translate message");
                                }
                            });
                        }
                    }
                }, 0, 1);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                Log.err("Failed to read messages: " + e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            Log.err("Invalid Authentification key: " + e.getMessage());
        }
    }

    private void showDialog(String title, String message) {
        FullTextDialog baseDialog = new FullTextDialog();

        baseDialog.show(title, message);
    }

    private class ButtonSetting extends SettingsMenuDialog.SettingsTable.Setting {
        String name;
        Runnable clicked;

        public ButtonSetting(String name, Runnable clicked) {
            super(name);
            this.name = name;
            this.clicked = clicked;
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table) {
            table.button(name, clicked).margin(14).width(240f).pad(6);
            table.row();
        }
    }
}
