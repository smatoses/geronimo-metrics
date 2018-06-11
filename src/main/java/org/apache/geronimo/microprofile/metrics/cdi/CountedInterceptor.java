/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geronimo.microprofile.metrics.cdi;

import java.io.Serializable;
import java.lang.reflect.Executable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import javax.annotation.Priority;
import javax.enterprise.inject.Intercepted;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.interceptor.AroundConstruct;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.Counted;

@Counted
@Interceptor
@Priority(Interceptor.Priority.LIBRARY_BEFORE)
public class CountedInterceptor implements Serializable {
    @Inject
    private MetricRegistry registry;

    @Inject
    @Intercepted
    private Bean<?> bean;

    @Inject
    private BeanManager beanManager;

    private transient volatile ConcurrentMap<Executable, Meta> counters = new ConcurrentHashMap<>();

    @AroundConstruct
    public Object onConstructor(final InvocationContext context) throws Exception {
        return invoke(context, context.getConstructor());
    }

    @AroundInvoke
    public Object onMethod(final InvocationContext context) throws Exception {
        return invoke(context, context.getMethod());
    }

    private Object invoke(final InvocationContext context, final Executable executable) throws Exception {
        final Meta counter = findCounter(executable);
        counter.counter.inc();
        try {
            return context.proceed();
        } finally {
            if (!counter.skipDecrement) {
                counter.counter.dec();
            }
        }
    }

    private Meta findCounter(final Executable executable) {
        if (counters == null) {
            synchronized (this) {
                if (counters == null) {
                    counters = new ConcurrentHashMap<>();
                }
            }
        }
        Meta meta = counters.get(executable);
        if (meta == null) {
            final AnnotatedType<?> type = beanManager.createAnnotatedType(bean.getBeanClass());
            final Counted counted = Stream.concat(type.getMethods().stream(), type.getConstructors().stream())
                    .filter(it -> it.getJavaMember().equals(executable))
                    .findFirst()
                    .map(m -> m.getAnnotation(Counted.class))
                    .orElse(null);
            final String name = Names.findName(type.getJavaClass(), executable, counted == null ? null : counted.name(), counted != null && counted.absolute());

            final Counter counter = Counter.class.cast(registry.getMetrics().get(name));
            if (counter == null) {
                throw new IllegalStateException("No counter with name [" + name + "] found in registry [" + registry + "]");
            }
            meta = new Meta(counter, counted != null && counted.monotonic());
            counters.putIfAbsent(executable, meta);
        }
        return meta;
    }

    private static final class Meta {
        private final Counter counter;
        private final boolean skipDecrement;

        private Meta(final Counter counter, final boolean skipDecrement) {
            this.counter = counter;
            this.skipDecrement = skipDecrement;
        }
    }
}
