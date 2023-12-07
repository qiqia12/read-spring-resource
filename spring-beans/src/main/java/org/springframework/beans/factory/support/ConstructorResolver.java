/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanReference;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 *
 * <p>Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @author Phillip Webb
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see #resolveConstructorOrFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	// BeanWrapper-based construction

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
			@Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {
		//是包装bean的容器
		BeanWrapperImpl bw = new BeanWrapperImpl();
		//给包装对象设置一些属性
		this.beanFactory.initBeanWrapper(bw);

		//spring对这个bean进行实例化使用的构造函数
		Constructor<?> constructorToUse = null;
		//spring执行构造函数使用的是参数封装类
		ArgumentsHolder argsHolderToUse = null;
		//参与构造函数实例化过程的函数
		Object[] argsToUse = null;

		//如果传入参数的话,就直接使用传入的参数
		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		//没有传入参数的话就走else
		else {
			//如果在调用getBean方法的时候没有指定,则尝试从配置文件中解析
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				//获取BeanDefinition中解析完成的构造函数
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				//BeanDefinition中存在构造器函数并且存在构造函数的参数,赋值进行使用
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					//从缓存中找到了构造器,那么继续从缓存中寻找缓存的构造器函数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						//没有缓存的参数,就需要获取配置文件中配置的参数
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			//如果缓存中没有缓存的参数的话,即argsToResolve不为空,就需要解析配置的参数
			if (argsToResolve != null) {
				//参数解析类型,比如配置的String类型转换成list,boolean等类型
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
			}
		}

		//如果没有缓存,就需要从构造器函数开始解析
		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			//如果传入的构造器数组不为空,就使用传入的,否则通过反射获取class中定义的构造器
			Constructor<?>[] candidates = chosenCtors;
			if (candidates == null) {
				Class<?> beanClass = mbd.getBeanClass();
				try {
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				Constructor<?> uniqueCandidate = candidates[0];
				if (uniqueCandidate.getParameterCount() == 0) {
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			// Need to resolve the constructor.
			//自动装配标识,以下有一种情况成立即为true
			//1.传进来构造函数,证明spring根据之前代码的判断,知道应该用哪个构造函数
			//2.BeanDefinition中设置为构造函数注入模型
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			ConstructorArgumentValues resolvedValues = null;
			//构造函数的最小参数个数
			int minNrOfArgs;
			//如果传入了参与构造函数实例化的参数值,那么参数的数量即为最小参数个数
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				//提取配置文件中的配置的构造函数参数
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				//用于承载解析后的构造函数参数的值
				resolvedValues = new ConstructorArgumentValues();
				//能解析到的参数个数
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			//对候选的构造函数进行排序,先是访问权限后是参数个数
			//public权限参数数量由多到少
			AutowireUtils.sortConstructors(candidates);
			//定义一个差异变量,变量的大小决定着构造函数是否能被使用
			int minTypeDiffWeight = Integer.MAX_VALUE;
			//不明确的构造函数集合,正常情况下差异值不可能相同
			Set<Constructor<?>> ambiguousConstructors = null;
			Deque<UnsatisfiedDependencyException> causes = null;

			//循环候选的构造函数
			for (Constructor<?> candidate : candidates) {
				//获取参数的个数
				int parameterCount = candidate.getParameterCount();

				//如果已经找到选用的构造函数或者需要的参数个数小于当前的构造函数个数 则终止,前面已经经过了排序操作
				if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					break;
				}
				//如果本构造函数的参数列表数量小于要求的最小数量,则遍历下一个
				if (parameterCount < minNrOfArgs) {
					continue;
				}
				//存放构造函数解析完成的参数列表
				ArgumentsHolder argsHolder;
				//获取参数列表的类型
				Class<?>[] paramTypes = candidate.getParameterTypes();
				//存在需要解析的构造函数参数
				if (resolvedValues != null) {
					try {
						//获取构造函数上的ConstructorProperties注解中的参数
						String[] paramNames = null;
						if (resolvedValues.containsNamedArgument()) {
							paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
							//如果没有上面的注解,则获取构造函数参数列表中属性的名称
							if (paramNames == null) {
								//获取参数名称探索器
								ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
								if (pnd != null) {
									//获取指定构造函数的参数名称
									paramNames = pnd.getParameterNames(candidate);
								}
							}
						}
						//根据名称和数据类型创建采纳数持有者
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						if (causes == null) {
							causes = new ArrayDeque<>(1);
						}
						causes.add(ex);
						continue;
					}
				}
				else {
					// Explicit arguments given -> arguments length must match exactly.
					//如果参数列表的数量与传入进来的参数数量不相等,继续遍历,否则构造函数列表封装对象
					if (parameterCount != explicitArgs.length) {
						continue;
					}
					//构造函数没有参数的情况
					argsHolder = new ArgumentsHolder(explicitArgs);
				}
				//计算差异量
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				//本次的构造函数差异值小于上一个构造函数,则进行构造函数更换
				if (typeDiffWeight < minTypeDiffWeight) {
					//将确定使用的构造函数设置为本地构造
					constructorToUse = candidate;
					//更换使用的构造函数参数封装类
					argsHolderToUse = argsHolder;
					//更换参与构造函数实例化的参数
					argsToUse = argsHolder.arguments;
					//差异值更换
					minTypeDiffWeight = typeDiffWeight;
					//不明确的构造函数列表清空为null
					ambiguousConstructors = null;
				}
				//差异值相等,则表明构造函数不正常,放入异常集合
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			if (constructorToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities. " +
						"You should also check the consistency of arguments when mixing indexed and named arguments, " +
						"especially in case of bean definition inheritance)");
			}
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}
			/*
			 * 没有传入参与构造函数参数列表时,对构造函数缓存到BeanDefinition中
			 * 1.缓存BeanDefinition进行实例化时使用的构造函数
			 * 2.缓存BeanDefinition代表的Bean的构造函数已解析完标识
			 * 3.缓存参与构造函数参数列表值的参数列表
			 */
			if (explicitArgs == null && argsHolderToUse != null) {
				//将解析的构造函数加入缓存
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if ((!isStatic || isStaticCandidate(candidate, factoryClass)) && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (isParamMismatch(uniqueCandidate, candidate)) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		return (mbd.isNonPublicAccessAllowed() ?
				ReflectionUtils.getUniqueDeclaredMethods(factoryClass) : factoryClass.getMethods());
	}

	private boolean isStaticCandidate(Method method, Class<?> factoryClass) {
		return (Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass() == factoryClass);
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
		//创建实例包装类
		BeanWrapperImpl bw = new BeanWrapperImpl();
		//初始化实例包装类
		this.beanFactory.initBeanWrapper(bw);

		//工厂实例
		Object factoryBean;
		//工厂类型
		Class<?> factoryClass;
		//判断是否是静态的
		boolean isStatic;
		//工厂方法所属的类的简单名字
		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			//判断是否存在factoryBean,factoryBean创建出来的是工厂自身,会报异常,这样貌似等于无限递归创建了
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			//获取FactoryBean
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			//判断是否已经创建
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				throw new ImplicitlyAppearedSingletonException();
			}
			this.beanFactory.registerDependentBean(factoryBeanName, beanName);
			factoryClass = factoryBean.getClass();
			//表示非静态的
			isStatic = false;
		}
		else {
			// It's a static factory method on the bean class.
			//静态方法 没有factoryBean实例
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			//准备使用的工厂
			factoryBean = null;
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		//准备使用的工厂方法
		Method factoryMethodToUse = null;
		//准备使用的参数包装器
		ArgumentsHolder argsHolderToUse = null;
		//准备使用的参数
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			//如果有参数就是用参数
			argsToUse = explicitArgs;
		}
		else {
			//解析出的参数
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				//如果有工厂方法 且构造函数已经解析了
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
			}
		}
		//如果么解析过,就获取FactoryClass的用户定义类型,因为此时FactoryClass可能是CGLIB动态代理类型
		//所以要获取用父类的类型,如果工厂方法是唯一的,就是没重载的,就获取解析的工厂方法,如果不为空,就添加到一个不可变列表里
		//如果为空的话,就要出去找factoryClass的以及父类的所有的方法,进一步找出方法修饰符一致且名字跟工厂方法名字相同的且是bean注解的方法,并放入列表里

		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			//用户定义的类
			factoryClass = ClassUtils.getUserClass(factoryClass);
			//方法集合
			List<Method> candidates = null;
			//如果工厂方法是唯一的,没有重载的
			if (mbd.isFactoryMethodUnique) {
				if (factoryMethodToUse == null) {
					//获取解析的工厂方法
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				//存在的话 返回仅包含factoryMethodToUse的不可变列表
				if (factoryMethodToUse != null) {
					candidates = Collections.singletonList(factoryMethodToUse);
				}
			}
			//如果没找到工厂方法,可能有重载
			if (candidates == null) {
				candidates = new ArrayList<>();
				//获取factoryClass以及父类的所有方法作为候选的方法
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				//过滤出修饰符一样,工厂名一样且是bean注解的方法
				for (Method candidate : rawCandidates) {
					//如果isStatic修饰符一样且名字跟工厂方法名一样就添加
					if ((!isStatic || isStaticCandidate(candidate, factoryClass)) && mbd.isFactoryMethod(candidate)) {
						candidates.add(candidate);
					}
				}
			}
			//如果只获取到一个方法,且传入的参数为空,且没有设置构造方法参数值
			if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				//获取方法
				Method uniqueCandidate = candidates.get(0);
				//如果没有参数的话
				if (uniqueCandidate.getParameterCount() == 0) {
					//设置工厂方法
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					synchronized (mbd.constructorArgumentLock) {
						//设置解析出来的方法
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			if (candidates.size() > 1) {  // explicitly skip immutable singletonList
				candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
			}
			//构造器参数值
			ConstructorArgumentValues resolvedValues = null;
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			//最小的类型差距
			int minTypeDiffWeight = Integer.MAX_VALUE;
			//模糊的工厂方法集合
			Set<Method> ambiguousFactoryMethods = null;

			//最小参数个数
			int minNrOfArgs;
			if (explicitArgs != null) {
				//如果存在显示参数,就是显示参数个数
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				//如果存在构造器参数值,就解析出最小参数个数
				if (mbd.hasConstructorArgumentValues()) {
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				}
				else {
					minNrOfArgs = 0;
				}
			}

			Deque<UnsatisfiedDependencyException> causes = null;
			//遍历每个候选的方法,查看可以获取市里的匹配度
			for (Method candidate : candidates) {
				int parameterCount = candidate.getParameterCount();

				if (parameterCount >= minNrOfArgs) {
					ArgumentsHolder argsHolder;

					Class<?>[] paramTypes = candidate.getParameterTypes();
					//显示参数存在,如果长度不对,就直接下一个,否则就创建参数持有
					if (explicitArgs != null) {
						// Explicit arguments given -> arguments length must match exactly.
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							String[] paramNames = null;
							if (resolvedValues != null && resolvedValues.containsNamedArgument()) {
								ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
								if (pnd != null) {
									paramNames = pnd.getParameterNames(candidate);
								}
							}
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							if (causes == null) {
								causes = new ArrayDeque<>(1);
							}
							causes.add(ex);
							continue;
						}
					}
					//根据参数类型匹配,获取类型的差异值
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					//保存最小的,说明参数类型相近
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					//如果出Ian类型差异相同,参数个数也相同,而且需要严格判断,参数长度一样,参数类型一样
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			if (factoryMethodToUse == null || argsToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				if (explicitArgs != null) {
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				else if (resolvedValues != null) {
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found on class [" + factoryClass.getName() + "]: " +
						(mbd.getFactoryBeanName() != null ?
								"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() + "' on class [" +
						factoryClass.getName() + "]: needs to have a non-void return type!");
			}
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found on class [" + factoryClass.getName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	private Object instantiate(String beanName, RootBeanDefinition mbd,
			@Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			//获取实例化策略进行实例化
			return this.beanFactory.getInstantiationStrategy().instantiate(
					mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * <p>This method is also used for handling invocations of static factory methods.
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		int minNrOfArgs = cargs.getArgumentCount();

		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			int index = entry.getKey();
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			if (index + 1 > minNrOfArgs) {
				minNrOfArgs = index + 1;
			}
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			if (valueHolder.isConverted()) {
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			if (valueHolder.isConverted()) {
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		Set<String> allAutowiredBeanNames = new LinkedHashSet<>(paramTypes.length * 2);

		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			Class<?> paramType = paramTypes[paramIndex];
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			if (resolvedValues != null) {
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				usedValueHolders.add(valueHolder);
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				if (valueHolder.isConverted()) {
					convertedValue = valueHolder.getConvertedValue();
					args.preparedArguments[paramIndex] = convertedValue;
				}
				else {
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
								ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
								"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					Object sourceHolder = valueHolder.getSource();
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder constructorValueHolder) {
						Object sourceValue = constructorValueHolder.getValue();
						args.resolveNecessary = true;
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			}
			else {
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				try {
					ConstructorDependencyDescriptor desc = new ConstructorDependencyDescriptor(methodParam, true);
					Set<String> autowiredBeanNames = new LinkedHashSet<>(2);
					Object arg = resolveAutowiredArgument(
							desc, paramType, beanName, autowiredBeanNames, converter, fallback);
					if (arg != null) {
						setShortcutIfPossible(desc, paramType, autowiredBeanNames);
					}
					allAutowiredBeanNames.addAll(autowiredBeanNames);
					args.rawArguments[paramIndex] = arg;
					args.arguments[paramIndex] = arg;
					args.preparedArguments[paramIndex] = desc;
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}

		registerDependentBeans(executable, beanName, allAutowiredBeanNames);

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			Executable executable, Object[] argsToResolve) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		Class<?>[] paramTypes = executable.getParameterTypes();

		Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			Object argValue = argsToResolve[argIndex];
			Class<?> paramType = paramTypes[argIndex];
			boolean convertNecessary = false;
			if (argValue instanceof ConstructorDependencyDescriptor descriptor) {
				try {
					argValue = resolveAutowiredArgument(descriptor, paramType, beanName,
							null, converter, true);
				}
				catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					Set<String> autowiredBeanNames = null;
					if (descriptor.hasShortcut()) {
						// Reset shortcut and try to re-resolve it in this thread...
						descriptor.setShortcut(null);
						autowiredBeanNames = new LinkedHashSet<>(2);
					}
					logger.debug("Failed to resolve cached argument", ex);
					argValue = resolveAutowiredArgument(descriptor, paramType, beanName,
							autowiredBeanNames, converter, true);
					if (autowiredBeanNames != null && !descriptor.hasShortcut()) {
						// We encountered as stale shortcut before, and the shortcut has
						// not been re-resolved by another thread in the meantime...
						if (argValue != null) {
							setShortcutIfPossible(descriptor, paramType, autowiredBeanNames);
						}
						registerDependentBeans(executable, beanName, autowiredBeanNames);
					}
				}
			}
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
				convertNecessary = true;
			}
			else if (argValue instanceof String text) {
				argValue = this.beanFactory.evaluateBeanDefinitionString(text, mbd);
				convertNecessary = true;
			}
			if (convertNecessary) {
				MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
				try {
					argValue = converter.convertIfNecessary(argValue, paramType, methodParam);
				}
				catch (TypeMismatchException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
							"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
				}
			}
			resolvedArgs[argIndex] = argValue;
		}
		return resolvedArgs;
	}

	private Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Resolve the specified argument which is supposed to be autowired.
	 */
	@Nullable
	Object resolveAutowiredArgument(DependencyDescriptor descriptor, Class<?> paramType, String beanName,
			@Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {

		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + descriptor);
			}
			return injectionPoint;
		}

		try {
			return this.beanFactory.resolveDependency(descriptor, beanName, autowiredBeanNames, typeConverter);
		}
		catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		}
		catch (NoSuchBeanDefinitionException ex) {
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				if (paramType.isArray()) {
					return Array.newInstance(paramType.componentType(), 0);
				}
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				}
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			throw ex;
		}
	}

	private void setShortcutIfPossible(
			ConstructorDependencyDescriptor descriptor, Class<?> paramType, Set<String> autowiredBeanNames) {

		if (autowiredBeanNames.size() == 1) {
			String autowiredBeanName = autowiredBeanNames.iterator().next();
			if (this.beanFactory.containsBean(autowiredBeanName) &&
					this.beanFactory.isTypeMatch(autowiredBeanName, paramType)) {
				descriptor.setShortcut(autowiredBeanName);
			}
		}
	}

	private void registerDependentBeans(
			Executable executable, String beanName, Set<String> autowiredBeanNames) {

		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName + "' via " +
						(executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}
	}


	// AOT-oriented pre-resolution

	public Executable resolveConstructorOrFactoryMethod(String beanName, RootBeanDefinition mbd) {
		Supplier<ResolvableType> beanType = () -> getBeanType(beanName, mbd);
		List<ResolvableType> valueTypes = (mbd.hasConstructorArgumentValues() ?
				determineParameterValueTypes(mbd) : Collections.emptyList());
		Method resolvedFactoryMethod = resolveFactoryMethod(beanName, mbd, valueTypes);
		if (resolvedFactoryMethod != null) {
			return resolvedFactoryMethod;
		}

		Class<?> factoryBeanClass = getFactoryBeanClass(beanName, mbd);
		if (factoryBeanClass != null && !factoryBeanClass.equals(mbd.getResolvableType().toClass())) {
			ResolvableType resolvableType = mbd.getResolvableType();
			boolean isCompatible = ResolvableType.forClass(factoryBeanClass)
					.as(FactoryBean.class).getGeneric(0).isAssignableFrom(resolvableType);
			Assert.state(isCompatible, () -> String.format(
					"Incompatible target type '%s' for factory bean '%s'",
					resolvableType.toClass().getName(), factoryBeanClass.getName()));
			Constructor<?> constructor = resolveConstructor(beanName, mbd,
					() -> ResolvableType.forClass(factoryBeanClass), valueTypes);
			if (constructor != null) {
				return constructor;
			}
			throw new IllegalStateException("No suitable FactoryBean constructor found for " +
					mbd + " and argument types " + valueTypes);

		}

		Constructor<?> constructor = resolveConstructor(beanName, mbd, beanType, valueTypes);
		if (constructor != null) {
			return constructor;
		}

		throw new IllegalStateException("No constructor or factory method candidate found for " +
				mbd + " and argument types " + valueTypes);
	}

	private List<ResolvableType> determineParameterValueTypes(RootBeanDefinition mbd) {
		List<ResolvableType> parameterTypes = new ArrayList<>();
		for (ValueHolder valueHolder : mbd.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
			parameterTypes.add(determineParameterValueType(mbd, valueHolder));
		}
		for (ValueHolder valueHolder : mbd.getConstructorArgumentValues().getGenericArgumentValues()) {
			parameterTypes.add(determineParameterValueType(mbd, valueHolder));
		}
		return parameterTypes;
	}

	private ResolvableType determineParameterValueType(RootBeanDefinition mbd, ValueHolder valueHolder) {
		if (valueHolder.getType() != null) {
			return ResolvableType.forClass(
					ClassUtils.resolveClassName(valueHolder.getType(), this.beanFactory.getBeanClassLoader()));
		}
		Object value = valueHolder.getValue();
		if (value instanceof BeanReference br) {
			if (value instanceof RuntimeBeanReference rbr) {
				if (rbr.getBeanType() != null) {
					return ResolvableType.forClass(rbr.getBeanType());
				}
			}
			return ResolvableType.forClass(this.beanFactory.getType(br.getBeanName(), false));
		}
		if (value instanceof BeanDefinition innerBd) {
			String nameToUse = "(inner bean)";
			ResolvableType type = getBeanType(nameToUse,
					this.beanFactory.getMergedBeanDefinition(nameToUse, innerBd, mbd));
			return (FactoryBean.class.isAssignableFrom(type.toClass()) ?
					type.as(FactoryBean.class).getGeneric(0) : type);
		}
		if (value instanceof TypedStringValue typedValue) {
			if (typedValue.hasTargetType()) {
				return ResolvableType.forClass(typedValue.getTargetType());
			}
			return ResolvableType.forClass(String.class);
		}
		if (value instanceof Class<?> clazz) {
			return ResolvableType.forClassWithGenerics(Class.class, clazz);
		}
		return ResolvableType.forInstance(value);
	}

	@Nullable
	private Constructor<?> resolveConstructor(String beanName, RootBeanDefinition mbd,
			Supplier<ResolvableType> beanType, List<ResolvableType> valueTypes) {

		Class<?> type = ClassUtils.getUserClass(beanType.get().toClass());
		Constructor<?>[] ctors = this.beanFactory.determineConstructorsFromBeanPostProcessors(type, beanName);
		if (ctors == null) {
			if (!mbd.hasConstructorArgumentValues()) {
				ctors = mbd.getPreferredConstructors();
			}
			if (ctors == null) {
				ctors = (mbd.isNonPublicAccessAllowed() ? type.getDeclaredConstructors() : type.getConstructors());
			}
		}
		if (ctors.length == 1) {
			return ctors[0];
		}

		Function<Constructor<?>, List<ResolvableType>> parameterTypesFactory = executable -> {
			List<ResolvableType> types = new ArrayList<>();
			for (int i = 0; i < executable.getParameterCount(); i++) {
				types.add(ResolvableType.forConstructorParameter(executable, i));
			}
			return types;
		};
		List<Constructor<?>> matches = Arrays.stream(ctors)
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.NONE))
				.toList();
		if (matches.size() == 1) {
			return matches.get(0);
		}
		List<Constructor<?>> assignableElementFallbackMatches = Arrays
				.stream(ctors)
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.ASSIGNABLE_ELEMENT))
				.toList();
		if (assignableElementFallbackMatches.size() == 1) {
			return assignableElementFallbackMatches.get(0);
		}
		List<Constructor<?>> typeConversionFallbackMatches = Arrays
				.stream(ctors)
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.TYPE_CONVERSION))
				.toList();
		return (typeConversionFallbackMatches.size() == 1 ? typeConversionFallbackMatches.get(0) : null);
	}

	@Nullable
	private Method resolveFactoryMethod(String beanName, RootBeanDefinition mbd, List<ResolvableType> valueTypes) {
		if (mbd.isFactoryMethodUnique) {
			Method resolvedFactoryMethod = mbd.getResolvedFactoryMethod();
			if (resolvedFactoryMethod != null) {
				return resolvedFactoryMethod;
			}
		}

		String factoryMethodName = mbd.getFactoryMethodName();
		if (factoryMethodName != null) {
			String factoryBeanName = mbd.getFactoryBeanName();
			Class<?> factoryClass;
			boolean isStatic;
			if (factoryBeanName != null) {
				factoryClass = this.beanFactory.getType(factoryBeanName);
				isStatic = false;
			}
			else {
				factoryClass = this.beanFactory.resolveBeanClass(mbd, beanName);
				isStatic = true;
			}

			Assert.state(factoryClass != null, () -> "Failed to determine bean class of " + mbd);
			Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
			List<Method> candidates = new ArrayList<>();
			for (Method candidate : rawCandidates) {
				if ((!isStatic || isStaticCandidate(candidate, factoryClass)) && mbd.isFactoryMethod(candidate)) {
					candidates.add(candidate);
				}
			}

			Method result = null;
			if (candidates.size() == 1) {
				result = candidates.get(0);
			}
			else if (candidates.size() > 1) {
				Function<Method, List<ResolvableType>> parameterTypesFactory = method -> {
					List<ResolvableType> types = new ArrayList<>();
					for (int i = 0; i < method.getParameterCount(); i++) {
						types.add(ResolvableType.forMethodParameter(method, i));
					}
					return types;
				};
				result = resolveFactoryMethod(candidates, parameterTypesFactory, valueTypes);
			}

			if (result == null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found on class [" + factoryClass.getName() + "]: " +
						(mbd.getFactoryBeanName() != null ?
								"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "'. ");
			}
			return result;
		}

		return null;
	}

	@Nullable
	private Method resolveFactoryMethod(List<Method> executables,
			Function<Method, List<ResolvableType>> parameterTypesFactory,
			List<ResolvableType> valueTypes) {

		List<Method> matches = executables.stream()
				.filter(executable -> match(parameterTypesFactory.apply(executable), valueTypes, FallbackMode.NONE))
				.toList();
		if (matches.size() == 1) {
			return matches.get(0);
		}
		List<Method> assignableElementFallbackMatches = executables.stream()
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.ASSIGNABLE_ELEMENT))
				.toList();
		if (assignableElementFallbackMatches.size() == 1) {
			return assignableElementFallbackMatches.get(0);
		}
		List<Method> typeConversionFallbackMatches = executables.stream()
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.TYPE_CONVERSION))
				.toList();
		Assert.state(typeConversionFallbackMatches.size() <= 1,
				() -> "Multiple matches with parameters '" + valueTypes + "': " + typeConversionFallbackMatches);
		return (typeConversionFallbackMatches.size() == 1 ? typeConversionFallbackMatches.get(0) : null);
	}

	private boolean match(
			List<ResolvableType> parameterTypes, List<ResolvableType> valueTypes, FallbackMode fallbackMode) {

		if (parameterTypes.size() != valueTypes.size()) {
			return false;
		}
		for (int i = 0; i < parameterTypes.size(); i++) {
			if (!isMatch(parameterTypes.get(i), valueTypes.get(i), fallbackMode)) {
				return false;
			}
		}
		return true;
	}

	private boolean isMatch(ResolvableType parameterType, ResolvableType valueType, FallbackMode fallbackMode) {
		if (isAssignable(valueType).test(parameterType)) {
			return true;
		}
		return switch (fallbackMode) {
			case ASSIGNABLE_ELEMENT -> isAssignable(valueType).test(extractElementType(parameterType));
			case TYPE_CONVERSION -> typeConversionFallback(valueType).test(parameterType);
			default -> false;
		};
	}

	private Predicate<ResolvableType> isAssignable(ResolvableType valueType) {
		return parameterType -> (valueType == ResolvableType.NONE
				|| parameterType.isAssignableFrom(valueType));
	}

	private ResolvableType extractElementType(ResolvableType parameterType) {
		if (parameterType.isArray()) {
			return parameterType.getComponentType();
		}
		if (Collection.class.isAssignableFrom(parameterType.toClass())) {
			return parameterType.as(Collection.class).getGeneric(0);
		}
		return ResolvableType.NONE;
	}

	private Predicate<ResolvableType> typeConversionFallback(ResolvableType valueType) {
		return parameterType -> {
			if (valueOrCollection(valueType, this::isStringForClassFallback).test(parameterType)) {
				return true;
			}
			return valueOrCollection(valueType, this::isSimpleValueType).test(parameterType);
		};
	}

	private Predicate<ResolvableType> valueOrCollection(ResolvableType valueType,
			Function<ResolvableType, Predicate<ResolvableType>> predicateProvider) {

		return parameterType -> {
			if (predicateProvider.apply(valueType).test(parameterType)) {
				return true;
			}
			if (predicateProvider.apply(extractElementType(valueType)).test(extractElementType(parameterType))) {
				return true;
			}
			return (predicateProvider.apply(valueType).test(extractElementType(parameterType)));
		};
	}

	/**
	 * Return a {@link Predicate} for a parameter type that checks if its target
	 * value is a {@link Class} and the value type is a {@link String}. This is
	 * a regular use cases where a {@link Class} is defined in the bean
	 * definition as an FQN.
	 * @param valueType the type of the value
	 * @return a predicate to indicate a fallback match for a String to Class
	 * parameter
	 */
	private Predicate<ResolvableType> isStringForClassFallback(ResolvableType valueType) {
		return parameterType -> (valueType.isAssignableFrom(String.class) &&
				parameterType.isAssignableFrom(Class.class));
	}

	private Predicate<ResolvableType> isSimpleValueType(ResolvableType valueType) {
		return parameterType -> (BeanUtils.isSimpleValueType(parameterType.toClass()) &&
				BeanUtils.isSimpleValueType(valueType.toClass()));
	}

	@Nullable
	private Class<?> getFactoryBeanClass(String beanName, RootBeanDefinition mbd) {
		Class<?> beanClass = this.beanFactory.resolveBeanClass(mbd, beanName);
		return (beanClass != null && FactoryBean.class.isAssignableFrom(beanClass) ? beanClass : null);
	}

	private ResolvableType getBeanType(String beanName, RootBeanDefinition mbd) {
		ResolvableType resolvableType = mbd.getResolvableType();
		if (resolvableType != ResolvableType.NONE) {
			return resolvableType;
		}
		return ResolvableType.forClass(this.beanFactory.resolveBeanClass(mbd, beanName));
	}


	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}

	/**
	 * See {@link BeanUtils#getResolvableConstructor(Class)} for alignment.
	 * This variant adds a lenient fallback to the default constructor if available, similar to
	 * {@link org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor#determineCandidateConstructors}.
	 */
	@Nullable
	static Constructor<?>[] determinePreferredConstructors(Class<?> clazz) {
		Constructor<?> primaryCtor = BeanUtils.findPrimaryConstructor(clazz);

		Constructor<?> defaultCtor;
		try {
			defaultCtor = clazz.getDeclaredConstructor();
		}
		catch (NoSuchMethodException ex) {
			defaultCtor = null;
		}

		if (primaryCtor != null) {
			if (defaultCtor != null && !primaryCtor.equals(defaultCtor)) {
				return new Constructor<?>[] {primaryCtor, defaultCtor};
			}
			else {
				return new Constructor<?>[] {primaryCtor};
			}
		}

		Constructor<?>[] ctors = clazz.getConstructors();
		if (ctors.length == 1) {
			// A single public constructor, potentially in combination with a non-public default constructor
			if (defaultCtor != null && !ctors[0].equals(defaultCtor)) {
				return new Constructor<?>[] {ctors[0], defaultCtor};
			}
			else {
				return ctors;
			}
		}
		else if (ctors.length == 0) {
			// No public constructors -> check non-public
			ctors = clazz.getDeclaredConstructors();
			if (ctors.length == 1) {
				// A single non-public constructor, e.g. from a non-public record type
				return ctors;
			}
		}

		return null;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final Object[] rawArguments;

		public final Object[] arguments;

		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}


	/**
	 * DependencyDescriptor marker for constructor arguments,
	 * for differentiating between a provided DependencyDescriptor instance
	 * and an internally built DependencyDescriptor for autowiring purposes.
	 */
	@SuppressWarnings("serial")
	private static class ConstructorDependencyDescriptor extends DependencyDescriptor {

		@Nullable
		private volatile String shortcut;

		public ConstructorDependencyDescriptor(MethodParameter methodParameter, boolean required) {
			super(methodParameter, required);
		}

		public void setShortcut(@Nullable String shortcut) {
			this.shortcut = shortcut;
		}

		public boolean hasShortcut() {
			return (this.shortcut != null);
		}

		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			String shortcut = this.shortcut;
			return (shortcut != null ? beanFactory.getBean(shortcut, getDependencyType()) : null);
		}
	}


	private enum FallbackMode {

		NONE,

		ASSIGNABLE_ELEMENT,

		TYPE_CONVERSION
	}

}
