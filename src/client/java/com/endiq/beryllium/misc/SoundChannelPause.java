package com.endiq.beryllium.misc;

import com.endiq.beryllium.compat.Reflect;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reaches into the sound engine to pause the music that is already playing.
 *
 * <p>There is no API for this: the engine keeps a map of playing sounds to channel handles, and
 * a handle takes an action that runs on the audio thread. Both the map and the handle are named
 * here rather than typed, because both are internal and moved across the supported range — if
 * either is gone, this does nothing and music behaves as it always did.
 */
public final class SoundChannelPause {
	private SoundChannelPause() {
	}

	/** Runs {@code channel.pause()} on the audio thread for whichever channel it is given. */
	private static final Consumer<Object> PAUSE = channelConsumer("pause");

	/** Pauses every playing sound whose source is music. */
	public static void pauseMusic(Object soundEngine) {
		forEachChannel(soundEngine, PAUSE, "MUSIC");
	}

	private static void forEachChannel(Object soundEngine, Consumer<Object> action, String source) {
		if (soundEngine == null) {
			return;
		}
		try {
			Object map = Reflect.get(soundEngine, "instanceToChannel");
			if (!(map instanceof Map<?, ?> channels)) {
				return;
			}
			for (Map.Entry<?, ?> entry : channels.entrySet()) {
				Object instance = entry.getKey();
				if (instance == null || !source.equals(String.valueOf(Reflect.call(instance, "getSource")))) {
					continue;
				}
				Reflect.call(entry.getValue(), "execute", action);
			}
		} catch (Throwable t) {
			// Leaving the music playing is what vanilla does.
		}
	}

	@SuppressWarnings("unchecked")
	private static Consumer<Object> channelConsumer(String method) {
		return (Consumer<Object>) Proxy.newProxyInstance(
				SoundChannelPause.class.getClassLoader(),
				new Class<?>[] { Consumer.class },
				(proxy, called, args) -> {
					// Only the functional method means anything here; equals/hashCode/toString
					// arrive with unrelated arguments and must not be treated as channels.
					if ("accept".equals(called.getName()) && args != null && args.length == 1) {
						Reflect.call(args[0], method);
					}
					return null;
				});
	}
}
