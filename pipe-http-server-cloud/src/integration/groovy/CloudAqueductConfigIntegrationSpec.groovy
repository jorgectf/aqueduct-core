import com.opentable.db.postgres.junit.EmbeddedPostgresRules
import com.opentable.db.postgres.junit.SingleInstancePostgresRule
import io.micronaut.context.ApplicationContext
import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.qualifiers.Qualifiers
import org.junit.ClassRule
import spock.lang.Shared
import spock.lang.Specification

import javax.inject.Named
import javax.sql.DataSource

class CloudAqueductConfigIntegrationSpec extends Specification {

    private static final String AQUEDUCT_PACKAGE = "com.tesco.aqueduct"

    @Shared @ClassRule SingleInstancePostgresRule pg = EmbeddedPostgresRules.singleInstance()
    DataSource dataSource

    def "Cloud aqueduct application is configured correctly so it starts up successfully"() {
        given: "required externalized environment properties"
        System.setProperty("PIPE_READ_PASSWORD", "some_pipe_read_password")
        System.setProperty("RUNSCOPE_PIPE_READ_PASSWORD", "some_runscope_pipe_read_password")
        System.setProperty("SUPPORT_PASSWORD", "some_support_password")
        System.setProperty("IDENTITY_URL", "http://some.identity.url")
        System.setProperty("IDENTITY_VALIDATE_TOKEN_PATH", "some/path/")
        System.setProperty("IDENTITY_ISSUE_TOKEN_PATH", "some/path/")
        System.setProperty("IDENTITY_CLIENT_ID", "some_client_id")
        System.setProperty("IDENTITY_CLIENT_SECRET", "some_secret")
        System.setProperty("NODE_A_CLIENT_UID", "some_client_uuid")
        System.setProperty("POSTGRE_SERVER", "some_value")
        System.setProperty("POSTGRE_DATABASE", "some_value")
        System.setProperty("POSTGRE_USERNAME", "some_value")
        System.setProperty("POSTGRE_PASSWORD", "some_value")

        and: "an application context with right environment"
        ApplicationContext applicationContext = ApplicationContext
            .build()
            .environments("integration")
            .build()
            .registerSingleton(DataSource, pg.embeddedPostgres.postgresDatabase, Qualifiers.byName("pipe"))
            .registerSingleton(DataSource, pg.embeddedPostgres.postgresDatabase, Qualifiers.byName("registry"))


        when: "the application is started"
        applicationContext.start()

        and: "all lazy beans are explicitly loaded"
        loadSingletonBeansUsing(applicationContext)

        then: "application is in running state i.e all beans with its dependencies are loaded successfully"
        applicationContext.isRunning()

        cleanup:
        applicationContext.stop()
    }

    def loadSingletonBeansUsing(ApplicationContext context) {
        context.getAllBeanDefinitions()
            .findAll { it.isSingleton() && isAqueductBean(it) }
            .forEach { beanDefinition ->
                if (beanDefinition.getAnnotation(Named) != null) {
                    loadNamed(beanDefinition, context)
                } else {
                    context.getBeansOfType(beanDefinition.beanType)
                }
            }
    }

    def loadNamed(BeanDefinition<?> beanDefinition, ApplicationContext context) {
        beanDefinition
            .getAnnotation(Named)
            .getValue(String)
            .map { context.getBean(beanDefinition.beanType, Qualifiers.byName(it)) }
            .orElseGet { context.getBean(beanDefinition.beanType) }
    }

    boolean isAqueductBean(BeanDefinition<?> it) {
        it.getName().contains(AQUEDUCT_PACKAGE)
    }
}
