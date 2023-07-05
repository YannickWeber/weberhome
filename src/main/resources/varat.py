#!/usr/bin/python3

import pytz, datetime
import pprint
import urllib.request
from influxdb import InfluxDBClient

INFLUXDB_ADDRESS = '192.168.1.6'
INFLUXDB_PORT = 8086
INFLUXDB_USER = 'username'
INFLUXDB_PASSWORD = 'password'
INFLUXDB_DATABASE = 'varta_element6'
influxdb_client = InfluxDBClient(INFLUXDB_ADDRESS, INFLUXDB_PORT, INFLUXDB_USER, INFLUXDB_PASSWORD, None)
databases = influxdb_client.get_list_database()
if len(list(filter(lambda x: x['name'] == INFLUXDB_DATABASE, databases))) == 0:
    influxdb_client.create_database(INFLUXDB_DATABASE)
    print('INFO', 'initialized DB')
influxdb_client.switch_database(INFLUXDB_DATABASE)

baseUrl = "http://192.168.1.15/"
content = urllib.request.urlopen(baseUrl + "cgi/ems_conf.js").read()
#print(content)
exec(content,globals(),locals())

# Wechselrichter
log_values = {
    'UVerbundL1' : 'netz_l1_u',
    'UVerbundL2' : 'netz_l2_u',
    'UVerbundL3' : 'netz_l3_u',
    'IVerbundL1' : 'netz_l1_i',
    'IVerbundL2' : 'netz_l2_i',
    'IVerbundL3' : 'netz_l3_i',
    'UInselL1' : 'in_l1_u',
    'UInselL2' : 'in_l2_u',
    'UInselL3' : 'in_l3_u',
    'IInselL1' : 'in_l1_1',
    'IInselL2' : 'in_l2_i',
    'IInselL3' : 'in_l3_i',
    'TempL1' : 'temp1',
    'TempL2' : 'temp2',
    'TempL3' : 'temp3',
    'FNetz' : 'netzfreq',
    'SystemState' : 'state',
    # Charger  1
    'U' : 'u',
    'I' : 'i',
    'THT' : 'temp',
    # Batterie  1
    'U_Rack' : 'u',
    'I_Rack' : 'i',
    # Batteriemodul 1
    'Status' : 'status',
    'U_Modul' : 'u',
    'I_Modul' : 'i',
    'UAvg_Modul' : 'u_avg',
    'TempAvg' : 'temp',
    'Cycles' : 'cycles',
    'CapRemain' : 'remain'
}

content = urllib.request.urlopen(baseUrl + "cgi/ems_data.js").read()
#print(str(content))
exec(content,globals(),locals())

# record extracted data in here
data = dict()
json_body = []

def print_key_value(key, value, name=''):
    #print(key, "=", value)
    if key in log_values: data[name + log_values[key]] = value

#print()
naive = datetime.datetime.strptime(Zeit, '%d.%m.%Y %H:%M:%S')
local = pytz.timezone('Europe/Berlin')
local_dt = local.localize(naive, is_dst=None)
utc_dt = local_dt.astimezone(pytz.utc)
timestamp = utc_dt.strftime('%Y-%m-%d %H:%M:%S')
print("Zeit", "=", Zeit, timestamp)
#print("--- Wechselrichter")
name = 'WR1'
all_data = dict()
for i, key in enumerate(WR_Conf):
    print_key_value(key.replace(" ",""), WR_Data[i])
    all_data[key.replace(" ","")] = WR_Data[i]
json_body.append({'time': timestamp, 'measurement': 'varta','tags':{'location':name}, 'fields':all_data})

#print()
for j in range(len(Charger_Data)):
    #print("--- Charger ", j + 1)
    name = 'C' + str(j+1)
    all_data = dict()
    for i, key in enumerate(Charger_Conf):
        if key == "BattData":
            BattData = Charger_Data[j][i];
        else:
            print_key_value(key, Charger_Data[j][i], 'charger' + str(j + 1) + "_")
            all_data[key] = Charger_Data[j][i]
    json_body.append({'time': timestamp, 'measurement':'varta', 'tags':{'location':name}, 'fields':all_data})

    #print()
    name = 'B' + str(j+1)
    all_data = dict()
    for i, key in enumerate(Batt_Conf):
        #print("--- Batterie ", j + 1)
        if key == "ModulData":
            ModulData = BattData[i];
        else:
            print_key_value(key, BattData[i], 'batt' + str(j + 1) + "_")
            all_data[key] = BattData[i]
    json_body.append({'time': timestamp, 'measurement':'varta', 'tags': {'location':name}, 'fields':all_data})

    #print()
    all_data = dict()
    for k in range(len(ModulData)):
        #print("--- Batteriemodul", k + 1)
        name = 'B' + str(j+1) + 'M' + str(k+1)
        for i, key in enumerate(Modul_Conf):
            print_key_value(key, ModulData[k][i], 'modul' + str(k + 1) + "_")
            all_data[key] = ModulData[k][i]
    json_body.append({'time': timestamp, 'measurement':'varta', 'tags': {'location':name}, 'fields':all_data})

## remove later
json_body.append({'time':timestamp, 'measurement':'battery', 'tags': {'location':'Varta'}, 'fields':data})

#pprint.pprint(json_body)
influxdb_client.write_points(json_body)
