package com.doctusoft.dynabean;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import java.util.*;

import static com.doctusoft.dynabean.Primitives.primitiveToWrapperType;
import static com.doctusoft.dynabean.Primitives.wrap;
import static java.util.Objects.*;

/**
 * Internal class for storing the immutable definition of a dynabean type (interface).
 */
final class BeanDefinition {
    
    static final Builder builder(Class<?> beanInterfaceClass) {
        return new Builder(beanInterfaceClass);
    }
    
    private final Class<?> beanInterfaceClass;
    
    private final LinkedHashMap<Method, MethodDefinition> propertyMethodMap;
    
    private BeanDefinition(Class<?> beanInterfaceClass, LinkedHashMap<Method, MethodDefinition> propertyMethodMap) {
        this.beanInterfaceClass = beanInterfaceClass;
        this.propertyMethodMap = propertyMethodMap;
    }
    
    public MethodDefinition getMethodDefinition(Method method) {
        return propertyMethodMap.get(method);
    }
    
    public Class<?> getBeanInterfaceClass() {
        return beanInterfaceClass;
    }
    
    public static final class Builder {
        
        private final Class<?> beanInterfaceClass;
        
        private LinkedHashMap<Method, MethodDefinition> methodDefinitionMap;
        
        private Builder(Class<?> beanInterfaceClass) {
            this.beanInterfaceClass = requireNonNull(beanInterfaceClass);
            this.methodDefinitionMap = new LinkedHashMap<>();
            for (Method method : beanInterfaceClass.getDeclaredMethods()) {
                MethodDefinition methodDefinition = defineIfProperty(method);
                if (methodDefinition != null) {
                    methodDefinitionMap.put(method, methodDefinition);
                }
            }
        }
        
        public Builder mergeSuperclassDefinition(BeanDefinition superclassDefinition) {
            for (Map.Entry<Method, MethodDefinition> e : superclassDefinition.propertyMethodMap.entrySet()) {
                Method method = e.getKey();
                if (!this.methodDefinitionMap.containsKey(method)) {
                    MethodDefinition definition = e.getValue();
                    this.methodDefinitionMap.put(method, definition);
                }
            }
            return this;
        }
        
        public BeanDefinition build() {
            LinkedHashMap<Method, MethodDefinition> theMap = methodDefinitionMap;
            methodDefinitionMap = null;
            return new BeanDefinition(beanInterfaceClass, theMap);
        }
    }

    TreeSet<String> copyPropertyNames() {
        TreeSet<String> propertyNames = new TreeSet<>();
        for (MethodDefinition methodDefinition : propertyMethodMap.values()) {
            if (methodDefinition instanceof GetterMethod) {
                propertyNames.add(((GetterMethod) methodDefinition).propertyName);
            }
        }
        return propertyNames;
    }
    
    private static MethodDefinition defineIfProperty(Method method) {
        if (JvmInternals.isDefaultMethod(method)) {
            Class<?> declaringClass = method.getDeclaringClass();
            Lookup privateLookup = JvmInternals.privateLookupOrNull(declaringClass);
            if (privateLookup == null) {
                return null;
            }
            try {
                return new DefaultMethod(privateLookup.unreflectSpecial(method, declaringClass));
            } catch (IllegalAccessException e) {
                throw new AssertionError(
                    "Private Lookup{" + privateLookup + "} failed to unrefletSpecial on method: " + method, e);
            }
        }
        String methodName = method.getName();
        if (methodName.length() < 4) {
            return null;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        StringBuilder buf = new StringBuilder(methodName);
        String prefix = buf.substring(0, 3);
        boolean getPrefix = prefix.equals("get");
        boolean isPrefix = prefix.startsWith("is") && Objects.equals(wrap(method.getReturnType()), Boolean.class);
        boolean getter = parameterTypes.length == 0 && (isPrefix || getPrefix);
        boolean setter = parameterTypes.length == 1 && prefix.equals("set");
        if (getter || setter) {
            int prefixLength = isPrefix ? 2 : 3;
            buf.setCharAt(prefixLength, Character.toLowerCase(buf.charAt(prefixLength)));
            String propertyName = buf.substring(prefixLength);
            if (getter) {
                Class<?> returnType = method.getReturnType();
                return new GetterMethod(returnType, propertyName);
            } else {
                return new SetterMethod(parameterTypes[0], propertyName);
            }
        }
        return null;
    }
    
    private static final class GetterMethod implements MethodDefinition {
        
        private final Class<?> type;
        private final Class<?> wrap;
        private final String propertyName;
        private final Object defaultValue;
        
        private GetterMethod(Class<?> type, String propertyName) {
            this.type = requireNonNull(type);
            this.wrap = Primitives.wrap(type);
            this.propertyName = requireNonNull(propertyName);
            this.defaultValue = type.isPrimitive() ? Primitives.defaultValue(type) : null;
        }
        
        public Object invoke(Object proxy, BeanProperties beanProperties, Object... arguments) {
            checkArguments(0, arguments);
            Object value = beanProperties.get(propertyName);
            if (value == null) {
                return defaultValue;
            } else if (wrap.isInstance(value)) {
                return value;
            } else {
                throw notInstanceOf(value, type);
            }
        }
    }
    
    private static final class SetterMethod implements MethodDefinition {
        
        private final Class<?> type;
        private final Class<?> wrap;
        private final String propertyName;
        
        private SetterMethod(Class<?> type, String propertyName) {
            this.type = requireNonNull(type);
            this.wrap = Primitives.wrap(type);
            this.propertyName = requireNonNull(propertyName);
        }
        
        public Object invoke(Object proxy, BeanProperties beanProperties, Object... arguments) {
            checkArguments(1, arguments);
            Object value = arguments[0];
            if ((value == null && wrap == type) || wrap.isInstance(value)) {
                beanProperties.set(propertyName, value);
                return null;
            } else {
                throw notInstanceOf(value, type);
            }
        }
    }
    
    private static final class DefaultMethod implements MethodDefinition {
        
        private final MethodHandle methodHandle;
        
        private DefaultMethod(MethodHandle methodHandle) {
            this.methodHandle = methodHandle;
        }
        
        public Object invoke(Object proxy, BeanProperties beanProperties, Object... arguments) throws Throwable {
            return methodHandle.bindTo(proxy).invokeWithArguments(arguments);
        }
    }
    
    private static void checkArguments(int expected, Object[] args) {
        int countArgs = args == null ? 0 : args.length;
        if (countArgs != expected) {
            throw new IllegalArgumentException("Expected " + expected + " argument(s), got: " + Arrays.toString(args));
        }
    }
    
    private static IllegalArgumentException notInstanceOf(Object value, Class<?> type) {
        return new IllegalArgumentException(value + " is not an instance of type: " + type);
    }
    
}
