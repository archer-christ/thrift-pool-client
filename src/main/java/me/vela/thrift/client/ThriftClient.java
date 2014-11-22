/**
 * 
 */
package me.vela.thrift.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.Proxy;
import javassist.util.proxy.ProxyFactory;
import me.vela.thrift.client.pool.ThriftConnectionPoolProvider;
import me.vela.thrift.client.pool.ThriftServerInfo;
import me.vela.thrift.client.pool.impl.DefaultThriftConnectionPoolImpl;

import org.apache.thrift.TServiceClient;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;

/**
 * @author w.vela
 *
 * @date 2014年7月14日 下午5:01:17
 */
public class ThriftClient {

    private static ConcurrentMap<Class<?>, Set<String>> interfaceMethodCache = new ConcurrentHashMap<>();

    private final ThriftConnectionPoolProvider poolProvider;

    private final Supplier<List<ThriftServerInfo>> servicesInfoProvider;

    private final Random random = new Random();

    /**
     * @param servicesInfoProvider
     */
    public ThriftClient(Supplier<List<ThriftServerInfo>> servicesInfoProvider) {
        this(servicesInfoProvider, DefaultThriftConnectionPoolImpl.getInstance());
    }

    /**
     * @param servicesInfoProvider
     * @param poolProvider
     */
    public ThriftClient(Supplier<List<ThriftServerInfo>> servicesInfoProvider,
            ThriftConnectionPoolProvider poolProvider) {
        this.poolProvider = poolProvider;
        this.servicesInfoProvider = servicesInfoProvider;
    }

    public <X extends TServiceClient> X iface(Class<X> ifaceClass) {
        return iface(ifaceClass, random.nextInt());
    }

    public <X extends TServiceClient> X iface(Class<X> ifaceClass, int hash) {
        return iface(ifaceClass, TCompactProtocol::new, hash);
    }

    @SuppressWarnings("unchecked")
    public <X extends TServiceClient> X iface(Class<X> ifaceClass,
            Function<TTransport, TProtocol> protocolProvider, int hash) {
        List<ThriftServerInfo> servers = servicesInfoProvider.get();
        if (servers == null || servers.isEmpty()) {
            throw new RuntimeException("no backend server.");
        }
        hash = Math.abs(hash);
        hash = hash < 0 ? 0 : hash;
        ThriftServerInfo selected = servers.get(hash % servers.size());

        TTransport transport = poolProvider.getConnection(selected);
        TProtocol protocol = protocolProvider.apply(transport);

        ProxyFactory factory = new ProxyFactory();
        factory.setSuperclass(ifaceClass);
        Set<String> interfaceMethodNames = interfaceMethodCache.computeIfAbsent(
                ifaceClass,
                t -> Stream.of(t.getInterfaces()).flatMap(i -> Stream.of(i.getMethods()))
                        .map(Method::getName).collect(Collectors.toSet()));
        factory.setFilter(m -> {
            if (m.getName().equals("finalize")) {
                return false;
            }
            return interfaceMethodNames.contains(m.getName());
        });
        MethodHandler handler = new MethodHandler() {

            private Boolean success;

            @Override
            public Object invoke(Object self, Method thisMethod, Method proceed, Object[] args)
                    throws Throwable {
                if (success != null) {
                    throw new RuntimeException(
                            "recall iface to get a new connection, you cannot reuse iface.");
                }
                success = false;
                try {
                    Object result = proceed.invoke(self, args);
                    success = true;
                    return result;
                } finally {
                    if (success) {
                        poolProvider.returnConnection(selected, transport);
                    } else {
                        poolProvider.returnBrokenConnection(selected, transport);
                    }
                }
            }
        };
        try {
            X x = (X) factory.create(new Class[] { org.apache.thrift.protocol.TProtocol.class },
                    new Object[] { protocol });
            ((Proxy) x).setHandler(handler);
            return x;
        } catch (NoSuchMethodException | IllegalArgumentException | InstantiationException
                | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("fail to create proxy.", e);
        }
    }
}
