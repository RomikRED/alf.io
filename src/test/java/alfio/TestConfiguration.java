/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio;

import alfio.config.support.PlatformProvider;
import alfio.test.util.IntegrationTestUtil;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import ru.yandex.qatools.embed.postgresql.EmbeddedPostgres;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Properties;

import static ru.yandex.qatools.embed.postgresql.distribution.Version.Main.PRODUCTION;

@Configuration
public class TestConfiguration {

    @Bean
    @Profile("!travis")
    public PlatformProvider getCloudProvider(EmbeddedPostgres postgres) {
        IntegrationTestUtil.generateDBConfig(postgres.getConnectionUrl().orElseThrow(IllegalArgumentException::new), EmbeddedPostgres.DEFAULT_USER, EmbeddedPostgres.DEFAULT_PASSWORD)
            .forEach(System::setProperty);
        return PlatformProvider.DEFAULT;
    }

    @Bean
    @Profile("!travis")
    public DataSource getDataSource(EmbeddedPostgres postgres) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getConnectionUrl().orElseThrow(IllegalArgumentException::new));
        dataSource.setUsername(EmbeddedPostgres.DEFAULT_USER);
        dataSource.setPassword(EmbeddedPostgres.DEFAULT_PASSWORD);
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setMaximumPoolSize(5);
        return dataSource;
    }

    @Bean
    @Profile("!travis")
    public EmbeddedPostgres postgres() throws IOException {
        EmbeddedPostgres postgres = new EmbeddedPostgres(PRODUCTION);
        Path pgsqlPath = Paths.get(System.getProperty("java.io.tmpdir"), "alfio-itest");
        postgres.start(EmbeddedPostgres.cachedRuntimeConfig(pgsqlPath));
        return postgres;
    }

    @PreDestroy
    public void shutdown(EmbeddedPostgres postgres) {
        postgres.stop();
    }


    @Bean
    public static PropertyPlaceholderConfigurer propConfig() {
        Properties properties = new Properties();
        properties.put("alfio.version", "1.9-SNAPSHOT");
        properties.put("alfio.build-ts", ZonedDateTime.now(ZoneId.of("UTC")).minusDays(1).toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out);
        properties.list(pw);
        pw.flush();
        PropertyPlaceholderConfigurer ppc =  new PropertyPlaceholderConfigurer();
        ppc.setLocation(new ByteArrayResource(out.toByteArray()));
        return ppc;
    }
}
