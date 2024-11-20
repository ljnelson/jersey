/*
 * Copyright (c) 2014, 2024 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.ext.cdi1x.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.logging.Logger;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.Producer;

import org.glassfish.jersey.internal.inject.DisposableSupplier;
import org.glassfish.jersey.internal.inject.InjectionManager;

/**
 * Abstract supplier to provide CDI components obtained from CDI bean manager.
 * The factory handles CDI managed components as well as non-contextual managed beans.
 * To specify scope of provided CDI beans, an extension of this supplier
 * should implement properly annotated {@link java.util.function.Supplier#get()} method that
 * could just delegate to the existing {@link #_provide()} method.
 *
 * @author Jakub Podlesak
 */
public abstract class AbstractCdiBeanSupplier<T> implements DisposableSupplier<T> {

    private static final Logger LOGGER = Logger.getLogger(AbstractCdiBeanSupplier.class.getName());

    final Class<T> clazz;
    final InstanceManager<T> referenceProvider;
    final Annotation[] qualifiers;
    /**
     * Create new factory instance for given type and bean manager.
     *
     * @param rawType          type of the components to provide.
     * @param injectionManager actual injection manager instance.
     * @param beanManager      current bean manager to get references from.
     * @param cdiManaged       set to {@code true} if the component should be managed by CDI.
     */
    public AbstractCdiBeanSupplier(final Class<T> rawType,
                                     final InjectionManager injectionManager,
                                     final BeanManager beanManager,
                                     final boolean cdiManaged) {

        this.clazz = rawType;
        this.qualifiers = CdiUtil.getQualifiers(clazz.getAnnotations(), beanManager);
        this.referenceProvider = cdiManaged ? new InstanceManager<T>() {

            final Instance<T> i = beanManager.createInstance().select(clazz);

            @Override
            public T getInstance(final Class<T> clazz) {
                assert clazz == AbstractCdiBeanSupplier.this.clazz;
                return i.get();
            }

            @Override
            public void preDestroy(final T instance) {
                i.destroy(instance);
            }
        } : new InstanceManager<T>() {

            final CdiComponentProvider cdiComponentProvider = beanManager.getExtension(CdiComponentProvider.class);
            final InjectionTarget<T> injectionTarget =
                beanManager.getInjectionTargetFactory(beanManager.createAnnotatedType(clazz))
                .createInjectionTarget(null);
            final CdiComponentProvider.InjectionManagerInjectedCdiTarget hk2managedTarget =
                cdiComponentProvider.new InjectionManagerInjectedCdiTarget(injectionTarget) {
                        @Override
                        public Set<InjectionPoint> getInjectionPoints() {
                            return injectionTarget.getInjectionPoints();
                        }
                    };

            {
                hk2managedTarget.setInjectionManager(injectionManager);
            }

            @Override
            public T getInstance(final Class<T> clazz) {
                assert clazz == AbstractCdiBeanSupplier.this.clazz;
                final CreationalContext<T> creationalContext = beanManager.createCreationalContext(null);
                final T instance = produce(injectionTarget, creationalContext, injectionManager, clazz);
                hk2managedTarget.inject(instance, creationalContext);
                hk2managedTarget.postConstruct(instance);
                return instance;
            }

            @Override
            public void preDestroy(final T instance) {
                hk2managedTarget.preDestroy(instance);
                hk2managedTarget.dispose(instance); // also releases affiliated CreationalContext, if it was ever used
            }
        };
    }

    /*
     * Try letting CDI produce the instance. If the constructor contains @Context Args CDI won't be able to produce it.
     * Let HK2 try to produce the target then.
     */
    private static <T> T produce(Producer<T> target, CreationalContext<T> ctx, InjectionManager im, Class<T> clazz) {
        try {
            return target.produce(ctx);
        } catch (Exception e) {
            LOGGER.fine(LocalizationMessages.CDI_FAILED_LOADING(clazz, e.getMessage()));
            try {
                return im.create(clazz);
            } catch (RuntimeException re) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                LOGGER.warning(LocalizationMessages.CDI_FAILED_LOADING(clazz, sw.toString()));
                throw re;
            }
        }
    }

    @SuppressWarnings(value = "unchecked")
    /* package */ T _provide() {
        final T instance = referenceProvider.getInstance(clazz);
        if (instance != null) {
            return instance;
        }
        throw new NoSuchElementException(LocalizationMessages.CDI_LOOKUP_FAILED(clazz));
    }

    @Override
    public void dispose(final T instance) {
        referenceProvider.preDestroy(instance);
    }

    private interface InstanceManager<T> {

        /**
         * Get me correctly instantiated and injected instance.
         *
         * @param clazz type of the component to instantiate.
         * @return injected component instance.
         */
        T getInstance(Class<T> clazz);

        /**
         * Do whatever needs to be done before given instance is destroyed.
         *
         * @param instance to be destroyed.
         */
        void preDestroy(T instance);
    }
}
