/*******************************************************************************
 * Copyright (c) 2022 EquoTech, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     EquoTech, Inc. - initial API and implementation
 *******************************************************************************/
package dev.equo.solstice;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import javax.xml.parsers.SAXParserFactory;
import org.eclipse.core.internal.runtime.CommonMessages;
import org.eclipse.osgi.framework.log.FrameworkLog;
import org.eclipse.osgi.internal.framework.EquinoxContainer;
import org.eclipse.osgi.internal.location.BasicLocation;
import org.eclipse.osgi.internal.location.EquinoxLocations;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.osgi.service.debug.DebugOptions;
import org.eclipse.osgi.service.environment.EnvironmentInfo;
import org.eclipse.osgi.service.localization.BundleLocalization;
import org.eclipse.osgi.service.urlconversion.URLConverter;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.log.LogLevel;
import org.osgi.service.packageadmin.PackageAdmin;

/** Controls the initialization of the {@link BundleContextSolstice} runtime. */
public class SolsticeIdeBootstrapServices {
	public static void apply(Map<String, String> props, BundleContext context) {
		EquinoxContainer container = new EquinoxContainer(props, null);
		registerLocations(context, container.getLocations());
		context.registerService(EnvironmentInfo.class, container.getConfiguration(), null);
		context.registerService(
				DebugOptions.class, container.getConfiguration().getDebugOptions(), null);

		// Provided by org.eclipse.osgi
		// - [x] org.eclipse.osgi.service.localization.BundleLocalization
		// - [x] org.eclipse.osgi.service.environment.EnvironmentInfo
		// - [x] org.osgi.service.packageadmin.PackageAdmin

		// - [x] org.eclipse.osgi.service.datalocation.Location,type=osgi.user.area
		// - [x] org.eclipse.osgi.service.datalocation.Location,type=osgi.instance.area
		// - [x] org.eclipse.osgi.service.datalocation.Location,type=osgi.configuration.area
		// - [x] org.eclipse.osgi.service.datalocation.Location,type=osgi.install.area
		// - [x] org.eclipse.osgi.service.datalocation.Location,type=eclipse.home.location

		// - [x] org.osgi.service.condition.Condition,osgi.condition.id=true

		// - [ ] org.osgi.service.log.LogReaderService
		// - [ ] org.eclipse.equinox.log.ExtendedLogReaderService
		// - [ ] org.osgi.service.log.LoggerFactory
		// - [ ] org.osgi.service.log.LogService
		// - [ ] org.eclipse.equinox.log.ExtendedLogService
		// - [ ] org.osgi.service.log.admin.LoggerAdmin
		// - [ ] org.eclipse.osgi.framework.log.FrameworkLog
		// - [ ] org.osgi.service.startlevel.StartLevel
		// - [ ] org.osgi.service.permissionadmin.PermissionAdmin
		// - [ ] org.osgi.service.condpermadmin.ConditionalPermissionAdmin
		// - [ ] org.osgi.service.resolver.Resolver
		// - [ ] org.eclipse.osgi.service.debug.DebugOptions
		// - [ ] org.eclipse.osgi.service.urlconversion.URLConverter
		// - [ ] org.eclipse.osgi.service.security.TrustEngine
		// - [ ] org.eclipse.osgi.signedcontent.SignedContentFactory
		var instanceDir =
				Unchecked.get(() -> new File(new URI(context.getProperty(Location.INSTANCE_AREA_TYPE))));

		Bundle systemBundle = context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION);
		// in particular, we need services normally provided by
		// org.eclipse.osgi.internal.framework.SystemBundleActivator::start
		context.registerService(
				BundleLocalization.class,
				(bundle, locale) -> {
					// TODO: we don't handle locale
					String localization = bundle.getHeaders().get(Constants.BUNDLE_LOCALIZATION);
					if (localization == null) {
						throw new MissingResourceException(
								NLS.bind(CommonMessages.activator_resourceBundleNotFound, locale),
								bundle.getSymbolicName(),
								""); //$NON-NLS-1$
					}
					URL url = bundle.getEntry(localization + ".properties");
					try (InputStream input = url.openStream()) {
						return new PropertyResourceBundle(input);
					} catch (IOException e) {
						throw Unchecked.wrap(e);
					}
				},
				Dictionaries.empty());
		context.registerService(
				PackageAdmin.class, systemBundle.adapt(PackageAdmin.class), Dictionaries.empty());

		context.registerService(
				SAXParserFactory.class, SAXParserFactory.newInstance(), Dictionaries.empty());

		var serviceManager = new ShimLogServiceManager(100, LogLevel.INFO, false);
		serviceManager.start(context);

		context.registerService(
				org.osgi.service.condition.Condition.class,
				new org.osgi.service.condition.Condition() {},
				Dictionaries.of("osgi.condition.id", "true"));
		context.registerService(
				FrameworkLog.class,
				Unchecked.get(
						() -> {
							var frameworkLog = new ShimFrameworkLog();
							boolean append = false;
							File logFile = new File(instanceDir, "log");
							logFile.getParentFile().mkdirs();
							frameworkLog.setFile(logFile, append);
							return frameworkLog;
						}),
				Dictionaries.empty());
		// make images work
		context.registerService(
				URLConverter.class,
				new JarUrlResolver(new File(instanceDir, "JarUrlResolver")),
				Dictionaries.of("protocol", "jar"));
	}

	static Collection<String> locationKeys() {
		return List.of(
				EquinoxLocations.PROP_USER_AREA,
				EquinoxLocations.PROP_INSTANCE_AREA,
				EquinoxLocations.PROP_CONFIG_AREA,
				EquinoxLocations.PROP_INSTALL_AREA,
				EquinoxLocations.PROP_HOME_LOCATION_AREA);
	}

	private static void registerLocations(BundleContext bc, EquinoxLocations locs) {
		registerLocation(bc, locs.getUserLocation(), EquinoxLocations.PROP_USER_AREA);
		registerLocation(bc, locs.getInstanceLocation(), EquinoxLocations.PROP_INSTANCE_AREA);
		registerLocation(bc, locs.getConfigurationLocation(), EquinoxLocations.PROP_CONFIG_AREA);
		registerLocation(bc, locs.getInstallLocation(), EquinoxLocations.PROP_INSTALL_AREA);
		registerLocation(bc, locs.getEclipseHomeLocation(), EquinoxLocations.PROP_HOME_LOCATION_AREA);
	}

	private static void registerLocation(BundleContext bc, BasicLocation location, String type) {
		if (location != null) {
			location.register(bc);
		}
	}

	static class JarUrlResolver implements URLConverter {
		private final File dir;

		JarUrlResolver(File dir) {
			this.dir = dir;
			if (!dir.exists()) {
				dir.mkdirs();
			}
		}

		@Override
		public URL toFileURL(URL url) throws IOException {
			var file = new File(dir, filenameSafe(url.toExternalForm()));
			if (!file.exists()) {
				byte[] content;
				try (var read = url.openStream()) {
					content = read.readAllBytes();
				}
				Files.write(file.toPath(), content);
			}
			return file.toURI().toURL();
		}

		@Override
		public URL resolve(URL url) throws IOException {
			throw Unimplemented.onPurpose();
		}

		private static final String DOT_JAR_EX_SLASH = ".jar!/";

		private static String filenameSafe(String url) {
			var dotJarIdx = url.indexOf(DOT_JAR_EX_SLASH);
			if (dotJarIdx == -1) {
				throw Unimplemented.onPurpose(
						"This is only for " + DOT_JAR_EX_SLASH + " urls, this was " + url);
			}

			var jarNameStart = url.lastIndexOf('/', dotJarIdx);

			var beforeJar = url.substring(0, jarNameStart);
			var jar = url.substring(jarNameStart + 1, dotJarIdx);
			var inZip = url.substring(dotJarIdx + DOT_JAR_EX_SLASH.length());

			return NestedJars.filenameSafeHash(beforeJar) + "--" + safe(jar) + "--" + safe(inZip);
		}

		private static String safe(String in) {
			String allSafeCharacters = in.replaceAll("[^a-zA-Z0-9-+_.]", "-");
			String noDuplicateDash = allSafeCharacters.replaceAll("-+", "-");
			return noDuplicateDash;
		}
	}
}
