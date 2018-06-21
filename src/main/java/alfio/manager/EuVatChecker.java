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
package alfio.manager;

import alfio.manager.system.ConfigurationManager;
import alfio.model.VatDetail;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.repository.AuditingRepository;
import ch.digitalfondue.vatchecker.EUVatCheckResponse;
import ch.digitalfondue.vatchecker.EUVatChecker;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static alfio.model.Audit.EntityType.RESERVATION;
import static alfio.model.Audit.EventType.VAT_VALIDATION_SUCCESSFUL;
import static alfio.model.system.Configuration.getSystemConfiguration;
import static alfio.model.system.ConfigurationKeys.APPLY_VAT_FOREIGN_BUSINESS;
import static java.util.Collections.singletonList;

@Component
@Log4j2
@RequiredArgsConstructor
public class EuVatChecker {

    private final ConfigurationManager configurationManager;
    private final AuditingRepository auditingRepository;
    private final EUVatChecker client = new EUVatChecker();

    private static final Cache<Pair<String, String>, EUVatCheckResponse> validationCache = Caffeine.newBuilder()
        .expireAfterWrite(15, TimeUnit.MINUTES)
        .build();

    public boolean isVatCheckingEnabledFor(int organizationId) {
        return reverseChargeEnabled(configurationManager, organizationId) && validationEnabled(configurationManager, organizationId);
    }

    public Optional<VatDetail> checkVat(String vatNr, String countryCode, int organizationId) {
        return performCheck(vatNr, countryCode, organizationId).apply(configurationManager, client);
    }

    static BiFunction<ConfigurationManager, EUVatChecker, Optional<VatDetail>> performCheck(String vatNr, String countryCode, int organizationId) {
        return (configurationManager, client) -> {
            boolean vatNrNotEmpty = StringUtils.isNotEmpty(vatNr);
            boolean validCountryCode = StringUtils.length(StringUtils.trimToNull(countryCode)) == 2;

            if(!vatNrNotEmpty || !validCountryCode) {
                return Optional.empty();
            }


            boolean euCountryCode = configurationManager.getRequiredValue(getSystemConfiguration(ConfigurationKeys.EU_COUNTRIES_LIST)).contains(countryCode);

            if(euCountryCode && validationEnabled(configurationManager, organizationId)) {
                EUVatCheckResponse validationResult = validateEUVat(vatNr, countryCode, client);
                return Optional.ofNullable(validationResult)
                    .map(r -> getVatDetail(reverseChargeEnabled(configurationManager, organizationId), r, vatNr, countryCode, organizerCountry(configurationManager, organizationId)));
            } else {
                String organizerCountry = organizerCountry(configurationManager, organizationId);
                Supplier<Boolean> applyVatToForeignBusiness = () -> configurationManager.getBooleanConfigValue(Configuration.from(organizationId, APPLY_VAT_FOREIGN_BUSINESS), true);
                return Optional.of(new VatDetail(vatNr, countryCode, true, "", "", !organizerCountry.equals(countryCode) && !applyVatToForeignBusiness.get()));
            }
        };
    }

    private static EUVatCheckResponse validateEUVat(String vat, String countryCode, EUVatChecker client) {

        if(StringUtils.isEmpty(vat) || StringUtils.length(countryCode) != 2) {
            return null;
        }

        return validationCache.get(Pair.of(vat, countryCode), k -> client.check(countryCode.toUpperCase(), vat));
    }

    private static VatDetail getVatDetail(boolean reverseChargeEnabled, EUVatCheckResponse response, String vatNr, String countryCode, String organizerCountryCode) {
        boolean isValid = response.isValid();
        return new VatDetail(vatNr, countryCode, isValid, response.getName(), response.getAddress(), isValid && reverseChargeEnabled && !organizerCountryCode.equals(countryCode));
    }

    private static String organizerCountry(ConfigurationManager configurationManager, int organizationId) {
        return configurationManager.getStringConfigValue(Configuration.from(organizationId, ConfigurationKeys.COUNTRY_OF_BUSINESS), null);
    }

    private static boolean reverseChargeEnabled(ConfigurationManager configurationManager, int organizationId) {
        return configurationManager.getBooleanConfigValue(Configuration.from(organizationId, ConfigurationKeys.ENABLE_EU_VAT_DIRECTIVE), false);
    }

    private static boolean validationEnabled(ConfigurationManager configurationManager, int organizationId) {
        return StringUtils.isNotEmpty(organizerCountry(configurationManager, organizationId));
    }

    @RequiredArgsConstructor
    public static class SameCountryValidator implements Predicate<String> {

        private final EuVatChecker checker;
        private final int organizationId;
        private final int eventId;
        private final String ticketReservationId;

        @Override
        public boolean test(String vatNr) {

            if(StringUtils.isEmpty(vatNr)) {
                log.warn("empty VAT number received for organizationId {}", organizationId);
            }

            String organizerCountry = organizerCountry(checker.configurationManager, organizationId);

            if(!validationEnabled(checker.configurationManager, organizationId)) {
                log.warn("VAT checking is not enabled for organizationId {} or country not defined ({})", organizationId, organizerCountry);
                return false;
            }

            EUVatCheckResponse result = validateEUVat(vatNr, organizerCountry, checker.client);
            boolean valid = result != null && result.isValid();
            if(valid && StringUtils.isNotEmpty(ticketReservationId)) {
                Map<String, Object> data = new HashMap<>();
                data.put("vatNumber", vatNr);
                data.put("country", organizerCountry);
                checker.auditingRepository.insert(ticketReservationId, null, eventId, VAT_VALIDATION_SUCCESSFUL, new Date(), RESERVATION, ticketReservationId, singletonList(data));
            }
            return valid;
        }
    }


}
