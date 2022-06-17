package me.thegiggitybyte.aliases;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class Aliases implements DedicatedServerModInitializer {
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    
    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var stringArg = argument("username", StringArgumentType.string())
                    .suggests((ctx, builder) -> CommandSource.suggestMatching(ctx.getSource().getPlayerNames(), builder))
                    .executes(Aliases::fetchUsernameHistory);
            
            var aliasesCommand = literal("aliases")
                    .requires(Permissions.require("aliases.use", true))
                    .then(stringArg).build();
            
            dispatcher.getRoot().addChild(aliasesCommand);
        });
    }
    
    private static int fetchUsernameHistory(CommandContext<ServerCommandSource> ctx) {
        var username = StringArgumentType.getString(ctx, "username");
        var uuidUri = URI.create("https://api.mojang.com/users/profiles/minecraft/" + username);
        var uuidRequest = HttpRequest.newBuilder(uuidUri).GET().build();
    
        httpClient.sendAsync(uuidRequest, HttpResponse.BodyHandlers.ofByteArray()).thenAcceptAsync(response -> {
            MutableText message;
            
            if (response.statusCode() == 200) {
                var uuid = parsePlayerUuid(response.body());
                var historyUri = URI.create("https://api.mojang.com/user/profiles/" + uuid + "/names");
                var historyRequest = HttpRequest.newBuilder(historyUri).GET().build();
                var historyResponse = httpClient.sendAsync(historyRequest, HttpResponse.BodyHandlers.ofByteArray()).join();
                
                message = parseUsernameHistory(historyResponse.body());
            } else if (response.statusCode() == 204) {
                message = Text.literal("Invalid username").formatted(Formatting.RED);
            } else {
                message = Text.literal("Could not fetch username history; HTTP " + response.statusCode()).formatted(Formatting.DARK_RED);
            }
    
            ctx.getSource().sendFeedback(message, false);
        });
        
        return 1;
    }
    
    private static String parsePlayerUuid(byte[] byteResponse) {
        var jsonStream = new ByteArrayInputStream(byteResponse);
        var jsonReader = new JsonReader(new InputStreamReader(jsonStream));
        
        try {
            jsonReader.beginObject();
            
            while (jsonReader.hasNext()) {
                if (jsonReader.nextName().equals("id"))
                    return jsonReader.nextString();
                else
                    jsonReader.skipValue();
            }
            
            jsonReader.endObject();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        throw new JsonParseException("Unable to parse UUID");
    }
    
    private static MutableText parseUsernameHistory(byte[] byteResponse) {
        var jsonStream = new ByteArrayInputStream(byteResponse);
        var jsonReader = new JsonReader(new InputStreamReader(jsonStream));
        
        var dateFormatter = new SimpleDateFormat("MMMM dd yyyy HH:mm:ss");
        var usernames = new ArrayList<MutableText>();
        
        try {
            jsonReader.beginArray();
            
            while (jsonReader.hasNext()) {
                jsonReader.beginObject();
                jsonReader.nextName();
                
                var username = jsonReader.nextString();
                var formattedUsername = Text.literal(username);
                
                if (jsonReader.hasNext()) {
                    jsonReader.nextName();
                    
                    var unixTimestamp = jsonReader.nextLong();
                    var stringTimestamp = dateFormatter.format(new Date(unixTimestamp));
                    var formattedTimestamp = Text.literal(stringTimestamp).formatted(Formatting.DARK_PURPLE);
                    var timestampHover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, formattedTimestamp);
                    
                    formattedUsername.setStyle(Style.EMPTY.withHoverEvent(timestampHover));
                    formattedUsername.formatted(Formatting.WHITE);
                    
                } else {
                    var originalText = Text.literal("Original username").formatted(Formatting.GRAY);
                    var originalTooltip = new HoverEvent(HoverEvent.Action.SHOW_TEXT, originalText);
                    
                    formattedUsername.setStyle(Style.EMPTY.withHoverEvent(originalTooltip));
                    formattedUsername.formatted(Formatting.GRAY);
                }
                
                usernames.add(formattedUsername);
                jsonReader.endObject();
            }
            
            jsonReader.endArray();
            jsonReader.close();
        } catch (Exception e) {
            e.printStackTrace();
            return Text.literal("Something went wrong while parsing username history").formatted(Formatting.DARK_RED, Formatting.BOLD);
        }
        
        Collections.reverse(usernames);
        var currentUsername = usernames.remove(0);
        
        var separatorLength = 18 + currentUsername.getString().length();
        var separatorString = new String(new char[separatorLength]).replace('\u0000', '-');
        var separator = Text.literal("\n" + separatorString + "\n").formatted(Formatting.YELLOW);
        
        var historyMessage = Text.literal("Username history for ").formatted(Formatting.WHITE)
                .append(currentUsername.formatted(Formatting.GOLD))
                .append(separator);
        
        if (usernames.size() > 0) {
            for (MutableText username : usernames) {
                historyMessage.append(username).append("\n");
            }
        } else {
            historyMessage.append(Text.literal("No previous usernames").formatted(Formatting.DARK_GRAY));
        }
        
        return historyMessage;
    }
}
