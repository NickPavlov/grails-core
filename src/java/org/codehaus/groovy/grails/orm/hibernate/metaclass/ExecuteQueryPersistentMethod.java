/* Copyright 2004-2005 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.orm.hibernate.metaclass;

import groovy.lang.Closure;
import groovy.lang.MissingMethodException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.codehaus.groovy.grails.orm.hibernate.cfg.GrailsHibernateUtil;
import org.codehaus.groovy.grails.orm.hibernate.exceptions.GrailsQueryException;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.orm.hibernate3.HibernateCallback;

/**
 * Allows the executing of arbitrary HQL queries.
 * <p/>
 * eg. Account.executeQuery("select distinct a.number from Account a where a.branch = ?", 'London') or
 * Account.executeQuery("select distinct a.number from Account a where a.branch = :branch", [branch:'London'])
 *
 * @author Graeme Rocher
 * @author Sergey Nebolsin
 * @see <a href="http://www.hibernate.org/hib_docs/reference/en/html/queryhql.html">http://www.hibernate.org/hib_docs/reference/en/html/queryhql.html</a>
 * @since 30-Apr-2006
 */
public class ExecuteQueryPersistentMethod extends AbstractStaticPersistentMethod {

    public static SimpleTypeConverter converter = new SimpleTypeConverter();
    private static final String METHOD_SIGNATURE = "executeQuery";
    private static final Pattern METHOD_PATTERN = Pattern.compile("^executeQuery$");

    public ExecuteQueryPersistentMethod(SessionFactory sessionFactory, ClassLoader classLoader) {
        super(sessionFactory, classLoader, METHOD_PATTERN);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Object doInvokeInternal(Class clazz, String methodName, Closure additionalCriteria, Object[] arguments) {
        checkMethodSignature(clazz, arguments);

        final String query = arguments[0].toString();
        final Map paginateParams = extractPaginateParams(arguments);
        final List positionalParams = extractPositionalParams(arguments);
        final Map namedParams = extractNamedParams(arguments);

        return getHibernateTemplate().executeFind(new HibernateCallback<Object>() {
            public Object doInHibernate(Session session) throws HibernateException, SQLException {
                Query q = session.createQuery(query);
                // process paginate params
                if (paginateParams.containsKey(GrailsHibernateUtil.ARGUMENT_MAX)) {
                    Integer maxParam = converter.convertIfNecessary(paginateParams.get(GrailsHibernateUtil.ARGUMENT_MAX),Integer.class);
                    q.setMaxResults(maxParam.intValue());
                }
                if (paginateParams.containsKey(GrailsHibernateUtil.ARGUMENT_OFFSET)) {
                    Integer offsetParam = converter.convertIfNecessary(paginateParams.remove(GrailsHibernateUtil.ARGUMENT_OFFSET), Integer.class);
                    q.setFirstResult(offsetParam.intValue());
                }
                if (paginateParams.containsKey(GrailsHibernateUtil.ARGUMENT_CACHE)) {
                    q.setCacheable(((Boolean)paginateParams.remove(GrailsHibernateUtil.ARGUMENT_CACHE)).booleanValue());
                }
                // process positional HQL params
                int index = 0;
                for (Object parameter : positionalParams) {
                    q.setParameter(index++, parameter instanceof CharSequence ? parameter.toString() : parameter);
                }
                // process named HQL params
                for (Object o : namedParams.entrySet()) {
                    Map.Entry entry = (Map.Entry) o;
                    if (!(entry.getKey() instanceof String)) {
                        throw new GrailsQueryException("Named parameter's name must be of type String");
                    }
                    String parameterName = (String) entry.getKey();
                    Object parameterValue = entry.getValue();
                    if (Collection.class.isAssignableFrom(parameterValue.getClass())) {
                        q.setParameterList(parameterName, (Collection) parameterValue);
                    }
                    else if (parameterValue.getClass().isArray()) {
                        q.setParameterList(parameterName, (Object[]) parameterValue);
                    }
                    else if (parameterValue instanceof CharSequence) {
                        q.setParameter(parameterName, parameterValue.toString());
                    }
                    else {
                        q.setParameter(parameterName, parameterValue);
                    }
                }
                return q.list();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void checkMethodSignature(Class clazz, Object[] arguments) {
        boolean valid = true;
        if (arguments.length < 1) valid = false;
        else if (arguments.length == 3 && !(arguments[2] instanceof Map)) valid = false;
        else if (arguments.length > 3) valid = false;

        if (!valid) throw new MissingMethodException(METHOD_SIGNATURE, clazz, arguments);
    }

    @SuppressWarnings("unchecked")
    private Map extractPaginateParams(Object[] arguments) {
        Map result = new HashMap();
        int paginateParamsIndex = 0;
        if (arguments.length == 2 && arguments[1] instanceof Map) paginateParamsIndex = 1;
        else if (arguments.length == 3) paginateParamsIndex = 2;
        if (paginateParamsIndex > 0) {
            Map sourceMap = (Map) arguments[paginateParamsIndex];
            if (sourceMap.containsKey(GrailsHibernateUtil.ARGUMENT_MAX)) result.put(GrailsHibernateUtil.ARGUMENT_MAX, sourceMap.get(GrailsHibernateUtil.ARGUMENT_MAX));
            if (sourceMap.containsKey(GrailsHibernateUtil.ARGUMENT_OFFSET)) result.put(GrailsHibernateUtil.ARGUMENT_OFFSET, sourceMap.get(GrailsHibernateUtil.ARGUMENT_OFFSET));
            if (sourceMap.containsKey(GrailsHibernateUtil.ARGUMENT_CACHE)) result.put(GrailsHibernateUtil.ARGUMENT_CACHE, sourceMap.get(GrailsHibernateUtil.ARGUMENT_CACHE));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List extractPositionalParams(Object[] arguments) {
        List result = new ArrayList();
        if (arguments.length < 2 || arguments[1] instanceof Map) return result;
        if (arguments[1] instanceof Collection) {
            result.addAll((Collection) arguments[1]);
        }
        else if (arguments[1].getClass().isArray()) {
            result.addAll(Arrays.asList((Object[]) arguments[1]));
        }
        else {
            result.add(arguments[1]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map extractNamedParams(Object[] arguments) {
        Map result = new HashMap();
        if (arguments.length < 2 || !(arguments[1] instanceof Map)) return result;
        result.putAll((Map) arguments[1]);
        // max, offset and cache are processed by paginate params
        result.remove(GrailsHibernateUtil.ARGUMENT_MAX);
        result.remove(GrailsHibernateUtil.ARGUMENT_OFFSET);
        result.remove(GrailsHibernateUtil.ARGUMENT_CACHE);
        return result;
    }
}
