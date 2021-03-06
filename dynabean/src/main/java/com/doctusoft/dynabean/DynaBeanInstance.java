package com.doctusoft.dynabean;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.*;

import static java.util.Objects.*;

/**
 * Internal implementation class of the proxy invoker of a dynabean instance.
 */
final class DynaBeanInstance implements InvocationHandler, BeanProperties {

    static <T> T createProxy(BeanDefinition beanDefinition, TreeMap<String, Object> propertiesMap) {
        Class<?>[] interfaces = { beanDefinition.beanInterfaceClass, DynaBean.class };
        DynaBeanInstance invoker = new DynaBeanInstance(beanDefinition, propertiesMap);
        Object dynaBeanInstance = Proxy.newProxyInstance(beanDefinition.classLoader, interfaces, invoker);
        return (T) dynaBeanInstance;
    }

    final BeanDefinition beanDefinition;

    private final TreeMap<String, Object> propertiesMap;

    DynaBeanInstance(BeanDefinition beanDefinition) {
        this.beanDefinition = requireNonNull(beanDefinition);
        this.propertiesMap = new TreeMap<>();
    }

    DynaBeanInstance(BeanDefinition beanDefinition, TreeMap<String, Object> propertiesMap) {
        this.beanDefinition = requireNonNull(beanDefinition);
        this.propertiesMap = requireNonNull(propertiesMap);
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodDefinition methodDefinition = beanDefinition.getMethodDefinition(method);
        if (methodDefinition != null) {
            return methodDefinition.invoke(proxy, this, args);
        }
        if (method.getDeclaringClass().equals(DynaBean.class)) {
            String methodName = method.getName();
            if (methodName.equals("clone")) {
                return cloneProxy();
            }
        }
        if (method.getDeclaringClass().equals(Object.class)) {
            String methodName = method.getName();
            if (methodName.equals("equals")) {
                args[0] = asDynaBeanInstanceOrNull(args[0]);
            }
            return method.invoke(this, args);
        }
        throw new UnsupportedOperationException("Unimplemented dynabean method: " + method);
    }

    public Object get(String propertyName) {
        return propertiesMap.get(propertyName);
    }

    public void set(String propertyName, Object value) {
        if (value == null) {
            propertiesMap.remove(propertyName);
        } else {
            propertiesMap.put(propertyName, value);
        }
    }

    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj instanceof DynaBeanInstance) {
            DynaBeanInstance other = (DynaBeanInstance) obj;
            return beanDefinition.equals(other.beanDefinition)
                && propertiesMap.equals(other.propertiesMap);
        }
        return false;
    }

    public int hashCode() {
        return 961 + 31 * beanDefinition.hashCode() + propertiesMap.hashCode();
    }

    public String toString() {
        return "DynaBean(type=" + beanDefinition.beanInterfaceClass.getSimpleName() + ")";
    }

    public Object cloneProxy() {
        TreeMap<String, Object> copy = new TreeMap<>();
        for (Entry<String, Object> entry : propertiesMap.entrySet()) {
            copy.put(entry.getKey(), copyPropertyValue(entry.getValue()));
        }
        return createProxy(beanDefinition, copy);
    }

    static BeanProperties accessProperties(Object dynabean) {
        if (!isProxyWithDynaBeanMarker(dynabean)) {
            throw new IllegalArgumentException("Not a dynabean instance: " + dynabean);
        }
        InvocationHandler invocationHandler = Proxy.getInvocationHandler(dynabean);
        if (!DynaBeanInstance.class.isInstance(invocationHandler)) {
            throw new IllegalArgumentException("Unrecognized invocationHandler: " + invocationHandler);
        }
        return (BeanProperties) invocationHandler;
    }

    static boolean isProxyWithDynaBeanMarker(Object instance) {
        return (instance instanceof DynaBean) && Proxy.isProxyClass(instance.getClass());
    }

    static DynaBeanInstance asDynaBeanInstanceOrNull(Object instance) {
        if (!isProxyWithDynaBeanMarker(instance)) return null;
        InvocationHandler invocationHandler = Proxy.getInvocationHandler(instance);
        return invocationHandler instanceof DynaBeanInstance ? (DynaBeanInstance) invocationHandler : null;
    }

    static Object copyPropertyValue(Object original) {
        if (List.class.isInstance(original)) {
            List originalList = (List) original;
            ArrayList copy = new ArrayList(originalList.size());
            for (Object element : originalList) {
                copy.add(copyPropertyValue(element));
            }
            return copy;
        }
        if (Set.class.isInstance(original)) {
            Set originalSet = (Set) original;
            Set copy = new LinkedHashSet();
            for (Object element : originalSet) {
                copy.add(copyPropertyValue(element));
            }
            return copy;
        }
        DynaBeanInstance dynabean = asDynaBeanInstanceOrNull(original);
        if (dynabean != null) {
            return dynabean.cloneProxy();
        }
        if (original instanceof Cloneable) {
            try {
                Method cloneMethod = original.getClass().getMethod("clone");
                return cloneMethod.invoke(original);

            } catch (NoSuchMethodException e) {
                return original;
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to clone value. " + e.getMessage(), e);
            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                throw new RuntimeException("Failed to clone value. " + targetException.getMessage(), targetException);
            }
        }
        return original;
    }

}
