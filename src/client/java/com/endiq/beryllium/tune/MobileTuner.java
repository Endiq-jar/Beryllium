package com.endiq.beryllium.tune;

import com.endiq.beryllium.capability.GraphicsCapabilityTier;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MobileTuner {
	private MobileTuner() {
	}

	private record OptionTarget(String fieldName, String enumClassName, String enumConstantName, Object plainValue) {
	}

	private static final OptionTarget[] PRESET = {
		new OptionTarget("particles", "net.minecraft.client.particles.ParticleMode", "MINIMAL", null),
		new OptionTarget("entityShadows", null, null, Boolean.FALSE),
		new OptionTarget("clouds", "net.minecraft.client.option.CloudRenderMode", "OFF", null),
		new OptionTarget("biomeBlend", null, null, Boolean.FALSE),
		new OptionTarget("bobView", null, null, Boolean.FALSE)
	};

	public static void applyIfEligible(BerylliumConfig config, GraphicsCapabilityTier tier) {
		if (!config.enabled || !config.autoTuneWeakDevices || config.autoTuneApplied) {
			return;
		}
		if (tier != GraphicsCapabilityTier.COMPATIBILITY && tier != GraphicsCapabilityTier.STANDARD) {
			BerylliumLog.mobile("Auto-tune skipped: device tier " + tier + " does not need the low-end preset.");
			return;
		}

		Object options;
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null) {
				return;
			}
			options = minecraft.options;
		} catch (Throwable t) {
			BerylliumLog.warn("Auto-tune aborted: could not access client options (" + t + ").");
			return;
		}

		if (options == null) {
			return;
		}

		BerylliumLog.mobile("Applying low-end device video preset (tier " + tier + ")...");
		int applied = 0;

		for (OptionTarget target : PRESET) {
			Object value = target.plainValue();
			if (value == null) {
				value = enumConstant(target.enumClassName(), target.enumConstantName());
				if (value == null) {
					BerylliumLog.mobile("  - " + target.fieldName() + ": skipped (constant "
						+ target.enumClassName() + "." + target.enumConstantName() + " not found).");
					continue;
				}
			}

			if (setOption(options, target.fieldName(), value)) {
				applied++;
				BerylliumLog.mobile("  - " + target.fieldName() + " = " + value);
			} else {
				BerylliumLog.mobile("  - " + target.fieldName() + ": skipped (option not found or not settable).");
			}
		}

		if (applied > 0) {
			invokeNoArg(options, "save");
			config.autoTuneApplied = true;
			config.save();
			BerylliumLog.mobile("Auto-tune complete: " + applied + "/" + PRESET.length + " options applied. "
				+ "They are saved in options.txt and can be changed in the video settings screen at any time.");
		} else {
			BerylliumLog.warn("Auto-tune applied nothing (options API mismatch?); leaving vanilla settings untouched.");
		}
	}

	private static Object enumConstant(String className, String constantName) {
		try {
			Class<?> clazz = Class.forName(className);
			for (Object constant : clazz.getEnumConstants()) {
				if (constantName.equals(((Enum<?>) constant).name())) {
					return constant;
				}
			}
			return null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean setOption(Object options, String fieldName, Object value) {
		try {
			Field field = options.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			Object instance = field.get(options);
			if (instance == null) {
				return false;
			}

			Method set = findSingleArgMethod(instance.getClass(), "set");
			if (set == null) {
				return false;
			}
			set.invoke(instance, value);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	private static Method findSingleArgMethod(Class<?> clazz, String name) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == 1) {
					method.setAccessible(true);
					return method;
				}
			}
		}
		return null;
	}

	private static void invokeNoArg(Object target, String name) {
		try {
			Class<?> clazz = target.getClass();
			while (clazz != null && clazz != Object.class) {
				try {
					Method method = clazz.getDeclaredMethod(name);
					method.setAccessible(true);
					method.invoke(target);
					return;
				} catch (NoSuchMethodException e) {
					clazz = clazz.getSuperclass();
				}
			}
		} catch (Throwable t) {
			BerylliumLog.warn("Could not call " + name + "() to persist options (" + t + "); changes still apply for this session.");
		}
	}
}
