-- Well-known Phnom Penh landmarks as a starting point for the landmark database.
-- Coordinates are APPROXIMATE (from general knowledge, not surveyed), so every row is unverified:
-- staff confirm each pin on the map in the web console before relying on it.

INSERT INTO place.places (id, name_km, name_en, latitude, longitude, place_type, verified) VALUES
(gen_random_uuid(), 'អាកាសយានដ្ឋានអន្តរជាតិតេជោ', 'Techo International Airport', 11.3650, 104.8620, 'AIRPORT', false),
(gen_random_uuid(), 'ព្រះបរមរាជវាំង', 'Royal Palace', 11.5641, 104.9312, 'LANDMARK', false),
(gen_random_uuid(), 'វត្តភ្នំ', 'Wat Phnom', 11.5762, 104.9230, 'LANDMARK', false),
(gen_random_uuid(), 'វិមានឯករាជ្យ', 'Independence Monument', 11.5564, 104.9282, 'LANDMARK', false),
(gen_random_uuid(), 'សារមន្ទីរជាតិ', 'National Museum', 11.5664, 104.9298, 'LANDMARK', false),
(gen_random_uuid(), 'វត្តឧណ្ណាលោម', 'Wat Ounalom', 11.5714, 104.9310, 'LANDMARK', false),
(gen_random_uuid(), 'ផ្សារធំថ្មី', 'Central Market (Phsar Thmei)', 11.5697, 104.9210, 'MARKET', false),
(gen_random_uuid(), 'ផ្សារទួលទំពូង', 'Russian Market (Toul Tom Poung)', 11.5429, 104.9172, 'MARKET', false),
(gen_random_uuid(), 'ផ្សាររាត្រីភ្នំពេញ', 'Phnom Penh Night Market', 11.5745, 104.9285, 'MARKET', false),
(gen_random_uuid(), 'ផ្សារទំនើបអ៊ីអន ១', 'AEON Mall Phnom Penh', 11.5480, 104.9343, 'MALL', false),
(gen_random_uuid(), 'ផ្សារទំនើបអ៊ីអន ២ សែនសុខ', 'AEON Mall Sen Sok City', 11.5996, 104.8795, 'MALL', false),
(gen_random_uuid(), 'ផ្សារទំនើបអ៊ីអន ៣ មានជ័យ', 'AEON Mall Mean Chey', 11.5130, 104.9190, 'MALL', false),
(gen_random_uuid(), 'ផ្សារទំនើបសូរិយា', 'Sorya Center Point', 11.5689, 104.9227, 'MALL', false),
(gen_random_uuid(), 'វឌ្ឍនៈ ខេភីថល', 'Vattanac Capital', 11.5735, 104.9200, 'OFFICE', false),
(gen_random_uuid(), 'ពហុកីឡដ្ឋានជាតិអូឡាំពិក', 'Olympic Stadium', 11.5584, 104.9119, 'LANDMARK', false),
(gen_random_uuid(), 'មន្ទីរពេទ្យកាល់ម៉ែត', 'Calmette Hospital', 11.5813, 104.9174, 'HOSPITAL', false),
(gen_random_uuid(), 'សាកលវិទ្យាល័យភូមិន្ទភ្នំពេញ', 'Royal University of Phnom Penh', 11.5687, 104.8902, 'SCHOOL', false),
(gen_random_uuid(), 'ស្ថានទូតសហរដ្ឋអាមេរិក', 'Embassy of the United States', 11.5778, 104.9227, 'EMBASSY', false),
(gen_random_uuid(), 'សារមន្ទីរឧក្រិដ្ឋកម្មប្រល័យពូជសាសន៍ទួលស្លែង', 'Tuol Sleng Genocide Museum', 11.5494, 104.9178, 'LANDMARK', false),
(gen_random_uuid(), 'មជ្ឈមណ្ឌលសន្និបាត និងពិព័រណ៍កោះពេជ្រ', 'Diamond Island Convention Center (Koh Pich)', 11.5508, 104.9369, 'LANDMARK', false),
(gen_random_uuid(), 'ស្ពានជ្រោយចង្វារ', 'Chroy Changvar Bridge', 11.5866, 104.9255, 'LANDMARK', false),
(gen_random_uuid(), 'កំពង់ផែស្វយ័តភ្នំពេញ', 'Phnom Penh Autonomous Port', 11.5772, 104.9298, 'TRANSPORT', false),
(gen_random_uuid(), 'ស្ថានីយ៍រថភ្លើងភ្នំពេញ', 'Phnom Penh Railway Station', 11.5719, 104.9163, 'TRANSPORT', false);
