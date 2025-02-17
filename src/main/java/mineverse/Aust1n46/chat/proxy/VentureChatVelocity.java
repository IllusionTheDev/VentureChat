package mineverse.Aust1n46.chat.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import mineverse.Aust1n46.chat.crypto.ChatEncryption;
import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import mineverse.Aust1n46.chat.database.ProxyPlayerData;
import mineverse.Aust1n46.chat.utilities.Format;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

/**
 * VentureChat Minecraft plugin for Velocity.
 *
 * @author Aust1n46
 */
public class VentureChatVelocity implements VentureChatProxySource {
	private final ProxyServer proxyServer;
	private final ChannelIdentifier channelIdentifier = MinecraftChannelIdentifier.create(VentureChatProxy.PLUGIN_MESSAGING_CHANNEL_NAMESPACE, VentureChatProxy.PLUGIN_MESSAGING_CHANNEL_NAME);
	private final Logger logger;
	
	@Inject
	@DataDirectory
	private Path dataPath;
	private File velocityPlayerDataDirectory;
	
	private static Configuration velocityConfig;
	
	@Inject
	public VentureChatVelocity(ProxyServer server, Logger logger) {
		this.proxyServer = server;
		this.logger = logger;
	}
	
	public static Configuration getVelocityConfig() {
		return velocityConfig;
	}
	
	@Subscribe
	public void onInitialize(ProxyInitializeEvent event) {
		proxyServer.getChannelRegistrar().register(channelIdentifier);
		
		File dataFolder = dataPath.toFile();
		if(!dataFolder.exists()) {
			dataFolder.mkdir();
		}
		File config = new File(dataFolder, "velocityconfig.yml");
		try {
			if(!config.exists()) {
				Files.copy(getClass().getClassLoader().getResourceAsStream("velocityconfig.yml"), config.toPath());
			}
			velocityConfig = ConfigurationProvider.getProvider(YamlConfiguration.class).load(new File(dataFolder, "velocityconfig.yml"));
			loadEncryptionKey();
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		
		velocityPlayerDataDirectory = new File(dataPath.toAbsolutePath().toString() + "/PlayerData");
		ProxyPlayerData.loadProxyPlayerData(velocityPlayerDataDirectory, this);
	}
	
	@Subscribe
	public void onShutdown(ProxyShutdownEvent event) {
		ProxyPlayerData.saveProxyPlayerData(velocityPlayerDataDirectory, this);
	}
	
	@Subscribe
	public void onPlayerJoin(ServerPostConnectEvent event) {
		updatePlayerNames();
	}
	
	@Subscribe
	public void onPlayerQuit(DisconnectEvent event) {
		// Delay sending plugin message to make sure disconnecting player is truly disconnected.
		proxyServer.getScheduler().buildTask(this, () -> {
			updatePlayerNames();
		})
		.delay(1, TimeUnit.SECONDS)
		.schedule();
	}
	
	private void updatePlayerNames() {
		try {
			ByteArrayOutputStream outstream = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(outstream);
			out.writeUTF("PlayerNames");
			out.writeInt(proxyServer.getPlayerCount());
			for(Player player : proxyServer.getAllPlayers()) {
				out.writeUTF(player.getUsername());
			}
			getServers().forEach(send -> {
				if(!send.isEmpty()) {
					sendPluginMessage(send.getName(), outstream.toByteArray());
				}
			});	
		}
		catch(IllegalStateException e) {
			sendConsoleMessage("Velocity being finicky with DisconnectEvent.");
		} 
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void loadEncryptionKey() {
		try {
			String key = velocityConfig.getString("encryption-key");

			if (key == null || key.isEmpty()) {
				key = ChatEncryption.generateKey();
				velocityConfig.set("encryption-key", key);
				saveConfig();
			}

			ChatEncryption.init(ChatEncryption.decodeKey(key));
		} catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
			throw new RuntimeException(e);
		}
	}

	private void saveConfig() {
		try {
			ConfigurationProvider.getProvider(YamlConfiguration.class).save(velocityConfig, new File(dataPath.toFile(), "velocityconfig.yml"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@Subscribe
	public void onPluginMessage(PluginMessageEvent event) {
		String channelIdentifierId = event.getIdentifier().getId();
		if(!channelIdentifierId.equals(VentureChatProxy.PLUGIN_MESSAGING_CHANNEL_STRING) && !channelIdentifierId.contains("viaversion:")) {
			return;
		}
		if(!(event.getSource() instanceof ServerConnection)) {
			return;
		}
		String serverName = ((ServerConnection) event.getSource()).getServerInfo().getName();

		byte[] decrypted = ChatEncryption.decrypt(event.getData());

		if(decrypted == null) {
			return; // exploit attempt
		}

		VentureChatProxy.onPluginMessage(decrypted, serverName, this);
		event.setResult(ForwardResult.handled());
	}

	@Override
	public void sendPluginMessage(String serverName, byte[] data) {
		Optional<RegisteredServer> server = proxyServer.getServer(serverName);
		if(server.isPresent()) {
            try {
                byte[] encrypted = ChatEncryption.encrypt(data);
				server.get().sendPluginMessage(channelIdentifier, encrypted);
			} catch (IllegalBlockSizeException | BadPaddingException e) {
                throw new RuntimeException(e);
            }
		}
	}

	@Override
	public List<VentureChatProxyServer> getServers() {
		return proxyServer.getAllServers().stream().map(velocityServer -> new VentureChatProxyServer(velocityServer.getServerInfo().getName(), velocityServer.getPlayersConnected().isEmpty())).collect(Collectors.toList());
	}

	@Override
	public VentureChatProxyServer getServer(String serverName) {
		RegisteredServer server = proxyServer.getServer(serverName).get();
		return new VentureChatProxyServer(serverName, server.getPlayersConnected().isEmpty());
	}

	@Override
	public void sendConsoleMessage(String message) {
		logger.info(Format.stripColor(message));
	}

	@Override
	public boolean isOfflineServerAcknowledgementSet() {
		return velocityConfig.getBoolean("offline_server_acknowledgement");
	}
}
