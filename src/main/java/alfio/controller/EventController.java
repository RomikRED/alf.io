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
package alfio.controller;


import alfio.controller.decorator.EventDescriptor;
import alfio.controller.decorator.SaleableTicketCategory;
import alfio.controller.form.ReservationForm;
import alfio.controller.support.SessionUtil;
import alfio.manager.EventManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.system.ConfigurationManager;
import alfio.model.Event;
import alfio.model.SpecialPrice;
import alfio.model.modification.TicketReservationWithOptionalCodeModification;
import alfio.model.modification.support.LocationDescriptor;
import alfio.repository.EventRepository;
import alfio.repository.SpecialPriceRepository;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.util.ErrorsCode;
import alfio.util.OptionalWrapper;
import alfio.util.ValidationResult;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;

import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Collectors;

import static alfio.model.system.ConfigurationKeys.MAPS_CLIENT_API_KEY;
import static alfio.model.system.ConfigurationKeys.MAX_AMOUNT_OF_TICKETS_BY_RESERVATION;
import static alfio.util.OptionalWrapper.optionally;

@Controller
public class EventController {

    private static final String REDIRECT = "redirect:";
    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final ConfigurationManager configurationManager;
    private final OrganizationRepository organizationRepository;
    private final SpecialPriceRepository specialPriceRepository;
	private final EventManager eventManager;
	private final TicketReservationManager ticketReservationManager;

	@Autowired
	public EventController(ConfigurationManager configurationManager,
						   TicketRepository ticketRepository,
						   EventRepository eventRepository,
						   OrganizationRepository organizationRepository,
						   TicketCategoryRepository ticketCategoryRepository,
						   SpecialPriceRepository specialPriceRepository, 
						   EventManager eventManager,
						   TicketReservationManager ticketReservationManager) {
		this.configurationManager = configurationManager;
		this.ticketRepository = ticketRepository;
		this.eventRepository = eventRepository;
		this.organizationRepository = organizationRepository;
		this.ticketCategoryRepository = ticketCategoryRepository;
		this.specialPriceRepository = specialPriceRepository;
		this.eventManager = eventManager;
		this.ticketReservationManager = ticketReservationManager;
	}

	@RequestMapping(value = {"/"}, method = RequestMethod.GET)
	public String listEvents(Model model) {
		List<Event> events = eventRepository.findAll();
		if(events.size() == 1) {
			return REDIRECT + "/event/" + events.get(0).getShortName() + "/";
		} else {
			model.addAttribute("events", events.stream().map(EventDescriptor::new).collect(Collectors.toList()));
			model.addAttribute("pageTitle", "event-list.header.title");
			model.addAttribute("event", null);
			return "/event/event-list";
		}
	}


	@RequestMapping("/session-expired")
	public String sessionExpired(Model model) {
		model.addAttribute("pageTitle", "session-expired.header.title");
		model.addAttribute("event", null);
		return "/event/session-expired";
	}

	@RequestMapping(value = "/event/{eventName}/promoCode/{promoCode}", method = RequestMethod.POST)
	@ResponseBody
	public ValidationResult savePromoCode(@PathVariable("eventName") String eventName,
								 @PathVariable("promoCode") String promoCode,
								 Model model,
								 HttpServletRequest request) {

		Optional<Event> optional = optionally(() -> eventRepository.findByShortName(eventName));
		if(!optional.isPresent()) {
			return ValidationResult.failed(new ValidationResult.ValidationError("event", ""));
		}
		Event event = optional.get();
		Optional<String> maybeSpecialCode = Optional.ofNullable(StringUtils.trimToNull(promoCode));
		Optional<SpecialPrice> specialCode = maybeSpecialCode.flatMap((trimmedCode) -> optionally(() -> specialPriceRepository.getByCode(trimmedCode)));
		if (maybeSpecialCode.isPresent() && (!specialCode.isPresent() || !optionally(() -> eventManager.getTicketCategoryById(specialCode.get().getTicketCategoryId(), event.getId())).isPresent())) {
			return ValidationResult.failed(new ValidationResult.ValidationError("promoCode", ""));
		}

		if (specialCode.isPresent() && specialCode.get().getStatus() != SpecialPrice.Status.FREE) {
			return ValidationResult.failed(new ValidationResult.ValidationError("promoCode", ""));
		}

		if(maybeSpecialCode.isPresent() && !model.asMap().containsKey("hasErrors")) {
			SessionUtil.saveSpecialPriceCode(maybeSpecialCode.get(), request);
			return ValidationResult.success();
		}
		return ValidationResult.failed(new ValidationResult.ValidationError("promoCode", ""));
	}

	@RequestMapping(value = "/event/{eventName}", method = RequestMethod.GET)
	public String showEvent(@PathVariable("eventName") String eventName,
							Model model, HttpServletRequest request) {

		
		Optional<Event> event = optionally(() -> eventRepository.findByShortName(eventName));
		
		if(!event.isPresent()) {
			return REDIRECT + "/";
		}
		
		Optional<String> maybeSpecialCode = SessionUtil.retrieveSpecialPriceCode(request);
		Optional<SpecialPrice> specialCode = maybeSpecialCode.flatMap((trimmedCode) -> optionally(() -> specialPriceRepository.getByCode(trimmedCode)));

		Event ev = event.get();
		final ZonedDateTime now = ZonedDateTime.now(ev.getZoneId());
		final int maxTickets = configurationManager.getIntConfigValue(MAX_AMOUNT_OF_TICKETS_BY_RESERVATION, 5);
		//hide access restricted ticket categories
		List<SaleableTicketCategory> t = ticketCategoryRepository.findAllTicketCategories(ev.getId()).stream()
                .filter((c) -> !c.isAccessRestricted() || (specialCode.isPresent() && specialCode.get().getTicketCategoryId() == c.getId()))
                .map((m) -> new SaleableTicketCategory(m, now, ev, ticketRepository.countUnsoldTicket(ev.getId(), m.getId()), maxTickets))
                .collect(Collectors.toList());
		//


		LocationDescriptor ld = LocationDescriptor.fromGeoData(ev.getLatLong(), TimeZone.getTimeZone(ev.getTimeZone()),
				configurationManager.getStringConfigValue(MAPS_CLIENT_API_KEY));
        final EventDescriptor eventDescriptor = new EventDescriptor(ev);
		model.addAttribute("event", eventDescriptor)//
			.addAttribute("organizer", organizationRepository.getById(ev.getOrganizationId()))
			.addAttribute("ticketCategories", t)//
			.addAttribute("hasAccessRestrictedCategory", ticketCategoryRepository.countAccessRestrictedRepositoryByEventId(ev.getId()).intValue() > 0)
			.addAttribute("promoCode", specialCode.map(SpecialPrice::getCode).orElse(null))
			.addAttribute("locationDescriptor", ld)
			.addAttribute("pageTitle", "show-event.header.title")
			.addAttribute("forwardButtonDisabled", t.stream().noneMatch(SaleableTicketCategory::getSaleable));
		model.asMap().putIfAbsent("hasErrors", false);//
		return "/event/show-event";
	}
	
	
	@RequestMapping(value = "/event/{eventName}/reserve-tickets", method = { RequestMethod.POST, RequestMethod.GET })
	public String reserveTicket(@PathVariable("eventName") String eventName,
			@ModelAttribute ReservationForm reservation, BindingResult bindingResult, Model model,
			ServletWebRequest request, RedirectAttributes redirectAttributes) {

		Optional<Event> event = OptionalWrapper.optionally(() -> eventRepository.findByShortName(eventName));
		if (!event.isPresent()) {
			return "redirect:/";
		}
		
		final String redirectToEvent = "redirect:/event/" + eventName + "/";

		if (request.getHttpMethod() == HttpMethod.GET) {
			return redirectToEvent;
		}

		Optional<List<TicketReservationWithOptionalCodeModification>> selected = reservation.validate(bindingResult, ticketReservationManager, eventManager, event.get(), request.getRequest());

		if (bindingResult.hasErrors()) {
			addToFlash(bindingResult, redirectAttributes);
			return redirectToEvent;
		}

		Date expiration = DateUtils.addMinutes(new Date(), TicketReservationManager.RESERVATION_MINUTE);

		try {
			String reservationId = ticketReservationManager.createTicketReservation(event.get().getId(),
					selected.get(), expiration, SessionUtil.retrieveSpecialPriceSessionId(request.getRequest()));
			return "redirect:/event/" + eventName + "/reservation/" + reservationId;
		} catch (TicketReservationManager.NotEnoughTicketsException nete) {
			bindingResult.reject(ErrorsCode.STEP_1_NOT_ENOUGH_TICKETS);
			addToFlash(bindingResult, redirectAttributes);
			return redirectToEvent;
		} catch (TicketReservationManager.MissingSpecialPriceTokenException missing) {
			bindingResult.reject(ErrorsCode.STEP_1_ACCESS_RESTRICTED);
			addToFlash(bindingResult, redirectAttributes);
			return redirectToEvent;
		} catch (TicketReservationManager.InvalidSpecialPriceTokenException invalid) {
			bindingResult.reject(ErrorsCode.STEP_1_CODE_NOT_FOUND);
			addToFlash(bindingResult, redirectAttributes);
			SessionUtil.removeSpecialPriceData(request.getRequest());
			return redirectToEvent;
		}
	}

	private static void addToFlash(BindingResult bindingResult, RedirectAttributes redirectAttributes) {
		redirectAttributes.addFlashAttribute("error", bindingResult).addFlashAttribute("hasErrors", true);
	}
}